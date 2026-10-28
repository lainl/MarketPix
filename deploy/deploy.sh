#!/usr/bin/env bash
# Deploys one MarketPix build on the server. The GitHub Actions runner calls it from the repository checkout:
#   bash deploy/deploy.sh <path to app.jar> <version>
# It builds the image, pauses new jobs and waits for running ones, swaps the container, checks its health,
# and goes back to the previous release when the new one does not become healthy.
set -Eeuo pipefail

ROOT=/opt/marketpix
STACK="$ROOT/stack"
RELEASE="$STACK/release.env"
DRAIN_MINUTES="${DRAIN_MINUTES:-30}"
JAR="$1"
VERSION="$2"
IMAGE="marketpix:$VERSION"
REPO="$(cd "$(dirname "$0")/.." && pwd)"

log() {
  echo "[deploy $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"
  logger -t marketpix-deploy -- "$*" 2>/dev/null || true
}

compose() {
  docker compose --project-directory "$STACK" -f "$STACK/compose.yaml" --env-file "$RELEASE" "$@"
}

release_value() {
  grep -E "^$1=" "$RELEASE" 2>/dev/null | cut -d= -f2- || true
}

app_status() {
  docker exec marketpix-app curl -fsS "http://127.0.0.1:3008/api/internal/$1" ${2:+-X "$2"} 2>/dev/null || true
}

json_number() {
  grep -o "\"$1\":[0-9]*" | cut -d: -f2 || true
}

wait_healthy() {
  local deadline=$((SECONDS + 240)) state
  while ((SECONDS < deadline)); do
    state="$(docker inspect --format '{{.State.Health.Status}}' marketpix-app 2>/dev/null || echo missing)"
    if [[ "$state" == healthy ]]; then
      return 0
    fi
    sleep 5
  done
  return 1
}

if [[ ! -f "$ROOT/.env" ]]; then
  log "The production settings are missing. Upload them to $ROOT/.env (see the README), then run the deploy again."
  exit 1
fi
SITE="$(cat "$ROOT/site")"

log "Building $IMAGE"
CONTEXT="$(mktemp -d)"
trap 'rm -rf "$CONTEXT"' EXIT
cp "$JAR" "$CONTEXT/app.jar"
cp -r "$REPO/MarketPix/UserInterface" "$CONTEXT/ui"
cp "$REPO/deploy/Dockerfile" "$CONTEXT/Dockerfile"
docker build --pull --quiet -t "$IMAGE" "$CONTEXT" >/dev/null

if ! docker run --rm -e "MARKETPIX_SITE=$SITE" -v "$REPO/deploy/Caddyfile:/etc/caddy/Caddyfile:ro" caddy:2 \
    caddy validate --config /etc/caddy/Caddyfile >/dev/null 2>&1; then
  log "The Caddyfile in this commit is not valid; nothing was changed on the server."
  exit 1
fi
install -m 644 "$REPO/deploy/compose.yaml" "$STACK/compose.yaml"
install -m 644 "$REPO/deploy/Caddyfile" "$STACK/Caddyfile"
PREVIOUS="$(release_value MARKETPIX_IMAGE)"

if [[ -n "$PREVIOUS" && "$(docker inspect --format '{{.State.Running}}' marketpix-app 2>/dev/null)" == true ]]; then
  log "Pausing new jobs on the running release ($PREVIOUS) and waiting for running jobs to finish"
  app_status drain POST >/dev/null
  deadline=$((SECONDS + DRAIN_MINUTES * 60))
  last_report=0
  while true; do
    status="$(app_status status)"
    active="$(echo "$status" | json_number activeJobs)"
    if [[ -z "$active" || "$active" == 0 ]]; then
      log "No jobs running; switching releases"
      break
    fi
    if ((SECONDS >= deadline)); then
      log "Still $active job(s) running after $DRAIN_MINUTES minutes; restarting anyway. Bulk jobs resume from their saved progress after the restart."
      break
    fi
    if ((SECONDS - last_report >= 60)); then
      log "Waiting for $active job(s) to finish ($(echo "$status" | json_number bulkPhotosLeft) bulk photos left)"
      last_report=$SECONDS
    fi
    sleep 10
  done
fi

printf 'MARKETPIX_IMAGE=%s\nMARKETPIX_PREVIOUS_IMAGE=%s\nMARKETPIX_SITE=%s\n' "$IMAGE" "$PREVIOUS" "$SITE" > "$RELEASE"
log "Starting $IMAGE"
compose up -d --remove-orphans
docker exec marketpix-caddy caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 \
  || log "Caddy did not reload its configuration; it keeps the previous one"

if wait_healthy; then
  log "MarketPix $VERSION is running and healthy at https://$SITE"
else
  log "The new release did not become healthy. Its last log lines:"
  docker logs --tail 40 marketpix-app 2>&1 || true
  if [[ -z "$PREVIOUS" ]]; then
    log "There is no earlier release to go back to."
    exit 1
  fi
  log "Going back to $PREVIOUS"
  printf 'MARKETPIX_IMAGE=%s\nMARKETPIX_PREVIOUS_IMAGE=\nMARKETPIX_SITE=%s\n' "$PREVIOUS" "$SITE" > "$RELEASE"
  compose up -d --remove-orphans
  if wait_healthy; then
    log "Rolled back: $PREVIOUS is running again"
  else
    log "The previous release did not become healthy either; check: docker logs marketpix-app"
  fi
  exit 1
fi

# Keeps the three newest MarketPix images so a rollback stays possible, and removes older ones.
docker images marketpix --format '{{.CreatedAt}}\t{{.Repository}}:{{.Tag}}' | sort -r | tail -n +4 | cut -f2 \
  | grep -vxF -e "$IMAGE" -e "$PREVIOUS" | xargs -r docker rmi >/dev/null 2>&1 || true

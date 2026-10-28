#!/usr/bin/env bash
# Goes back to the release that ran before the current one. Run on the server, or from the GitHub
# Actions "Test and deploy" workflow with the action "rollback".
set -Eeuo pipefail

STACK=/opt/marketpix/stack
RELEASE="$STACK/release.env"

log() {
  echo "[rollback $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"
  logger -t marketpix-deploy -- "rollback: $*" 2>/dev/null || true
}

value() {
  grep -E "^$1=" "$RELEASE" 2>/dev/null | cut -d= -f2- || true
}

CURRENT="$(value MARKETPIX_IMAGE)"
PREVIOUS="$(value MARKETPIX_PREVIOUS_IMAGE)"
SITE="$(value MARKETPIX_SITE)"
if [[ -z "$PREVIOUS" ]] || ! docker image inspect "$PREVIOUS" >/dev/null 2>&1; then
  log "There is no earlier release to go back to (current: ${CURRENT:-none})."
  exit 1
fi

log "Pausing new jobs; running bulk jobs resume from their saved progress after the switch"
docker exec marketpix-app curl -fsS -X POST http://127.0.0.1:3008/api/internal/drain >/dev/null 2>&1 || true
printf 'MARKETPIX_IMAGE=%s\nMARKETPIX_PREVIOUS_IMAGE=%s\nMARKETPIX_SITE=%s\n' "$PREVIOUS" "$CURRENT" "$SITE" > "$RELEASE"
docker compose --project-directory "$STACK" -f "$STACK/compose.yaml" --env-file "$RELEASE" up -d --remove-orphans

deadline=$((SECONDS + 240))
while ((SECONDS < deadline)); do
  if [[ "$(docker inspect --format '{{.State.Health.Status}}' marketpix-app 2>/dev/null)" == healthy ]]; then
    log "Rolled back from $CURRENT to $PREVIOUS"
    exit 0
  fi
  sleep 5
done
log "$PREVIOUS did not become healthy; check: docker logs marketpix-app"
exit 1

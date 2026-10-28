#!/usr/bin/env bash
# Connects the server to GitHub Actions as a self-hosted runner, so pushes to main deploy here. Run with
# sudo and the one-time registration token from GitHub (Settings > Actions > Runners > New self-hosted runner):
#   sudo RUNNER_TOKEN=<token> bash deploy/server/3-runner.sh
# The runner only dials out to GitHub; GitHub never logs in to the server, and no key or password is
# stored on GitHub. The token is used once and is useless afterwards.
set -Eeuo pipefail

REPO_URL=https://github.com/lainl/MarketPix
DIR=/opt/marketpix/runner
: "${RUNNER_TOKEN:?Set RUNNER_TOKEN to the registration token from GitHub}"

VERSION="$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest | grep -o '"tag_name": *"v[^"]*"' | grep -o '[0-9][0-9.]*')"
case "$(uname -m)" in
  x86_64) ARCH=x64 ;;
  aarch64) ARCH=arm64 ;;
  *) echo "Unsupported CPU: $(uname -m)"; exit 1 ;;
esac
echo "== Installing GitHub Actions runner $VERSION in $DIR"
cd "$DIR"
sudo -u marketpix curl -fsSL -o runner.tar.gz \
  "https://github.com/actions/runner/releases/download/v$VERSION/actions-runner-linux-$ARCH-$VERSION.tar.gz"
sudo -u marketpix tar xzf runner.tar.gz
rm -f runner.tar.gz
./bin/installdependencies.sh

sudo -u marketpix ./config.sh --unattended --replace --url "$REPO_URL" --token "$RUNNER_TOKEN" \
  --name marketpix-ovh --labels marketpix --work _work
./svc.sh install marketpix
./svc.sh start
./svc.sh status | head -n 5

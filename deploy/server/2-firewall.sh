#!/usr/bin/env bash
# Turns on the firewall. Run with sudo:  sudo bash deploy/server/2-firewall.sh
# Allows only SSH (on the port sshd really uses), HTTP and HTTPS in; everything else coming in is blocked.
# The app's own port 3008 is never published by Docker, so it stays unreachable from outside either way.
set -Eeuo pipefail

SSH_PORT="$(sshd -T 2>/dev/null | awk '/^port /{print $2}' | head -n 1 || true)"
SSH_PORT="${SSH_PORT:-22}"
echo "Keeping SSH open on port $SSH_PORT"

ufw default deny incoming
ufw default allow outgoing
ufw allow "$SSH_PORT/tcp" comment 'SSH'
ufw allow 80/tcp comment 'HTTP (certificates and redirect)'
ufw allow 443/tcp comment 'HTTPS'
ufw allow 443/udp comment 'HTTPS (HTTP/3)'
ufw --force enable
ufw status verbose

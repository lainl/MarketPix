#!/usr/bin/env bash
# Turns off SSH password logins and root logins. Run with sudo ONLY after key login works, and keep your
# current SSH session open until a new key login has been tested:  sudo bash deploy/server/4-ssh-hardening.sh
# sshd uses the first value it reads, so this file is named to be read before 50-cloud-init.conf.
set -Eeuo pipefail

CONF=/etc/ssh/sshd_config.d/01-marketpix-hardening.conf
if [[ -z "$(cat /home/*/.ssh/authorized_keys 2>/dev/null)" ]]; then
  echo "No authorized SSH key was found; leaving password logins on."
  exit 1
fi

cat > "$CONF" <<'CONF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
CONF

if ! sshd -t; then
  rm -f "$CONF"
  echo "The SSH configuration did not pass its check; nothing was changed."
  exit 1
fi
systemctl try-reload-or-restart ssh
sshd -T | grep -Ei '^(passwordauthentication|kbdinteractiveauthentication|permitrootlogin|pubkeyauthentication) '
echo "Done. Test a NEW key login before closing this session."

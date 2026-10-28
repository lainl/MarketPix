#!/usr/bin/env bash
# One-time server preparation. Run with sudo:  sudo bash deploy/server/1-install.sh
# Installs Docker and Docker Compose, automatic security updates and fail2ban, adds a swap file, and creates
# the marketpix user and the /opt/marketpix folders. It does not change the firewall or SSH.
set -Eeuo pipefail

SITE=marketpixs.site

echo "== Packages: certificates, curl, automatic security updates, fail2ban"
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl unattended-upgrades fail2ban

echo "== Docker Engine and Docker Compose"
. /etc/os-release
CODENAME="${UBUNTU_CODENAME:-$VERSION_CODENAME}"
if curl -fsI "https://download.docker.com/linux/ubuntu/dists/$CODENAME/Release" >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $CODENAME stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
else
  echo "Docker's repository has no packages for Ubuntu $CODENAME yet; using Ubuntu's own Docker packages."
  DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose-v2 docker-buildx
fi

# Container logs are rotated (5 files of 10 MB each), and containers keep running while Docker itself updates.
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "5" },
  "live-restore": true
}
JSON
systemctl enable docker
systemctl restart docker

echo "== Automatic security updates and fail2ban (blocks addresses that keep failing SSH logins)"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CONF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
CONF
systemctl enable --now unattended-upgrades fail2ban

echo "== 2 GB swap file, so a build next to the running app never runs out of memory"
if ! swapon --show | grep -q .; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== The marketpix user (no password, no SSH login) and its folders"
id marketpix >/dev/null 2>&1 || useradd --create-home --shell /bin/bash marketpix
passwd -l marketpix >/dev/null
usermod -aG docker marketpix
install -d -m 750 -o marketpix -g marketpix /opt/marketpix /opt/marketpix/stack /opt/marketpix/runner
# The app runs as user 10001 inside its container; only it can read the saved logins and bulk jobs.
install -d -m 700 -o 10001 -g 10001 /opt/marketpix/data /opt/marketpix/data/bulk-jobs
install -d -m 700 /opt/marketpix/caddy /opt/marketpix/caddy/data /opt/marketpix/caddy/config
echo "$SITE" > /opt/marketpix/site
chown marketpix:marketpix /opt/marketpix/site

echo
docker --version
docker compose version
echo "Done. Next: upload the production settings to /opt/marketpix/.env (see the README)."

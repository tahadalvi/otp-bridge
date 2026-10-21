#!/usr/bin/env bash
# Run ON the relay host, from ~/otp-relay, after the source has been copied there.
set -euo pipefail

cd "$(dirname "$0")/.."
RELAY_DIR="$(pwd)"

if [ ! -f .env ]; then
  echo "Creating .env with freshly generated tokens."
  PHONE=$(node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))")
  PC=$(node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))")
  cat > .env <<EOF
OTP_PHONE_TOKEN=$PHONE
OTP_PC_TOKEN=$PC
OTP_PORT=8790
OTP_PRIVATE_IP=10.0.0.2
EOF
  chmod 600 .env
  echo
  echo "PHONE_TOKEN (put in the Android app): $PHONE"
  echo "PC_TOKEN    (put in the PC agent):    $PC"
  echo
else
  echo ".env already exists — leaving it alone."
fi

# Must exist before the unit starts: systemd refuses a ReadWritePaths entry
# that points at nothing.
mkdir -p "${RELAY_DIR}/updates" "${RELAY_DIR}/state"

sudo cp deploy/otp-relay.service /etc/systemd/system/otp-relay.service
sudo sed -i "s#/home/ubuntu/otp-relay#${RELAY_DIR}#g" /etc/systemd/system/otp-relay.service
sudo sed -i "s#^User=.*#User=$(whoami)#" /etc/systemd/system/otp-relay.service
sudo systemctl daemon-reload
sudo systemctl enable --now otp-relay.service
sudo systemctl status otp-relay.service --no-pager

# Optional: expose only the /otp-bridge path publicly with Tailscale Funnel, so the
# phone can POST over HTTPS without opening any port on the host. Any reverse proxy
# that terminates TLS and forwards /otp-bridge to 127.0.0.1:8790 works just as well.
#
# Use `tailscale funnel`, NOT `tailscale serve`: `serve` makes the hostname
# private-network-only for every path, which silently takes any other public
# service on the same hostname offline.
tailscale funnel --bg --set-path /otp-bridge http://127.0.0.1:8790
tailscale funnel status

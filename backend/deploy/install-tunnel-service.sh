#!/usr/bin/env bash
# Runs the Cloudflare Tunnel as a systemd *user* service next to orbit-backend.service.
# Prerequisite: ~/.cloudflared/config.yml (see DEPLOYMENT.md, step 4).
set -euo pipefail

CONFIG="$HOME/.cloudflared/config.yml"
UNIT_DIR="$HOME/.config/systemd/user"
CLOUDFLARED="$(command -v cloudflared || true)"

if [[ -z "$CLOUDFLARED" ]]; then
	echo "cloudflared is not installed (see DEPLOYMENT.md, step 3)."
	exit 1
fi
if [[ ! -f "$CONFIG" ]]; then
	echo "Missing $CONFIG (see DEPLOYMENT.md, step 4)."
	exit 1
fi

mkdir -p "$UNIT_DIR"
cat > "$UNIT_DIR/orbit-tunnel.service" <<UNIT
[Unit]
Description=Cloudflare Tunnel for api.orbitcosmetics.qd.je
After=network-online.target orbit-backend.service
Wants=orbit-backend.service

[Service]
Type=simple
ExecStart=$CLOUDFLARED tunnel --no-autoupdate --config $CONFIG run
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
UNIT

systemctl --user daemon-reload
systemctl --user enable --now orbit-tunnel.service
systemctl --user --no-pager status orbit-tunnel.service | head -5 || true
echo "Logs: journalctl --user -u orbit-tunnel -f"

#!/usr/bin/env bash
# Installs the Orbit backend as a systemd *user* service (no root needed), plus a daily backup timer.
# Run from anywhere:  bash deploy/install-service.sh
set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="$BACKEND_DIR/.venv"
ENV_FILE="$HOME/.config/orbit-backend.env"
UNIT_DIR="$HOME/.config/systemd/user"

if [[ ! -x "$VENV/bin/uvicorn" ]]; then
	echo "No virtual environment found. First run:"
	echo "  cd \"$BACKEND_DIR\" && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"
	exit 1
fi

mkdir -p "$HOME/.config" "$UNIT_DIR" "$HOME/orbit-backups"
if [[ ! -f "$ENV_FILE" ]]; then
	sed "s|/home/YOUR_USER|$HOME|g" "$BACKEND_DIR/deploy/orbit-backend.env.example" > "$ENV_FILE"
	chmod 600 "$ENV_FILE"
	echo "Created $ENV_FILE (review it)."
fi
# The data folder from the env file.
DATA_DIR="$(grep -E '^ORBIT_DATA_DIR=' "$ENV_FILE" | cut -d= -f2-)"
[[ -n "$DATA_DIR" ]] && mkdir -p "$DATA_DIR"

cat > "$UNIT_DIR/orbit-backend.service" <<UNIT
[Unit]
Description=Orbit Cosmetics backend (api.orbitcosmetics.qd.je)
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$BACKEND_DIR
EnvironmentFile=$ENV_FILE
# Loopback only: the internet reaches it through Cloudflare Tunnel. Proxy headers are handled by the app.
ExecStart="$VENV/bin/uvicorn" main:app --host 127.0.0.1 --port 8000 --no-proxy-headers --no-server-header
Restart=on-failure
RestartSec=5
NoNewPrivileges=yes
PrivateTmp=yes

[Install]
WantedBy=default.target
UNIT

cat > "$UNIT_DIR/orbit-backup.service" <<UNIT
[Unit]
Description=Back up the Orbit backend database and cosmetics

[Service]
Type=oneshot
WorkingDirectory=$BACKEND_DIR
EnvironmentFile=$ENV_FILE
ExecStart="$VENV/bin/python" manage.py backup "$HOME/orbit-backups"
# Keep two weeks of backups.
ExecStartPost=/usr/bin/find "$HOME/orbit-backups" -mindepth 1 -maxdepth 1 -name "orbit-backup-*" -mtime +14 -exec rm -rf {} +
UNIT

cat > "$UNIT_DIR/orbit-backup.timer" <<UNIT
[Unit]
Description=Daily Orbit backend backup

[Timer]
OnCalendar=daily
Persistent=true

[Install]
WantedBy=timers.target
UNIT

# The Discord moderation bot, when its token is in backend/.env (TOKEN=...).
BOT_UNIT=""
if grep -q '^TOKEN=.' "$BACKEND_DIR/.env" 2>/dev/null; then
	cat > "$UNIT_DIR/orbit-bot.service" <<UNIT
[Unit]
Description=Orbit Cloud Discord moderation bot
After=network-online.target orbit-backend.service

[Service]
Type=simple
WorkingDirectory=$BACKEND_DIR
EnvironmentFile=$ENV_FILE
ExecStart="$VENV/bin/python" discord_bot.py
Restart=on-failure
RestartSec=30
NoNewPrivileges=yes
PrivateTmp=yes

[Install]
WantedBy=default.target
UNIT
	BOT_UNIT=orbit-bot.service
else
	echo "No TOKEN in $BACKEND_DIR/.env: the Discord bot is not installed."
fi

systemctl --user daemon-reload
systemctl --user enable --now orbit-backend.service orbit-backup.timer $BOT_UNIT
echo
systemctl --user --no-pager status orbit-backend.service | head -5 || true
echo
echo "Done. To keep it running while you are logged out (and after reboots), run once:"
echo "  sudo loginctl enable-linger $USER"
echo "Logs:    journalctl --user -u orbit-backend -f   (bot: -u orbit-bot)"
echo "Health:  curl http://127.0.0.1:8000/v1/health"

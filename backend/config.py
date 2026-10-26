"""Configuration, read from environment variables so tests and deployments can change it without code edits."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

APP_NAME = "Orbit Cosmetics Backend"
APP_VERSION = "0.3.0"
API_PREFIX = "/v1"

# The backend always listens on the loopback interface. In production it is reached through a reverse proxy or
# Cloudflare Tunnel running on the same machine, never directly.
HOST = "127.0.0.1"
PORT = 8000

MODE_DEVELOPMENT = "development"
MODE_PRODUCTION = "production"
MODE_READ_ONLY = "read-only"
MODES = (MODE_DEVELOPMENT, MODE_PRODUCTION, MODE_READ_ONLY)

MOJANG_HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined"


# Discord server where the moderation bot works (see discord_bot.py).
DISCORD_GUILD_ID = 1514172105787183114


def read_dotenv(path: Path) -> dict[str, str]:
    """Reads KEY=value lines from a .env file (secrets such as the bot token). Missing file: empty."""
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return values
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key.strip()] = value
    return values


def _environment() -> dict[str, str]:
    """backend/.env, overridden by real environment variables. The process environment is left untouched."""
    return {**read_dotenv(BASE_DIR / ".env"), **os.environ}


def _bool(value: str | None, default: bool) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _int(env: dict[str, str], name: str, default: int) -> int:
    value = env.get(name)
    return int(value) if value not in (None, "") else default


@dataclass(frozen=True)
class Settings:
    database_path: Path
    storage_dir: Path
    # 1 MiB. Uploads larger than this are rejected before being stored.
    max_upload_bytes: int = 1024 * 1024
    # development: writes allowed from this computer without accounts (local testing only).
    # production:  writes require a player session verified with Mojang; nothing destructive over HTTP.
    # read-only:   no writes at all.
    mode: str = MODE_DEVELOPMENT
    # Client addresses allowed to write in development mode.
    dev_hosts: frozenset[str] = field(default_factory=lambda: frozenset({"127.0.0.1", "::1", "localhost"}))
    # Use the visitor address that Cloudflare (CF-Connecting-IP) or a reverse proxy (X-Forwarded-For) reports,
    # but only for requests coming from this machine, i.e. from the tunnel or proxy itself.
    trust_proxy_headers: bool = True
    # Web origins allowed to read the API from a browser (the Orbit website).
    cors_origins: tuple[str, ...] = ()
    session_ttl_seconds: int = 24 * 3600
    uploads_per_player_per_day: int = 20
    max_storage_bytes: int = 1024 * 1024 * 1024
    requests_per_minute: int = 240
    auth_requests_per_minute: int = 10
    mojang_has_joined_url: str = MOJANG_HAS_JOINED_URL
    # Discord webhook told about players with many reports. Secret: set it in the environment, never in code.
    discord_webhook_url: str = ""
    # Reports are kept this long, then dropped.
    report_retention_days: int = 7
    # Distinct reporters (within the retention time) that trigger a Discord alert, and an automatic ban.
    report_alert_threshold: int = 3
    report_ban_threshold: int = 6
    report_ban_days: int = 7
    reports_per_player_per_day: int = 20
    # Discord moderation bot. The token is a secret: backend/.env (TOKEN=...) or ORBIT_DISCORD_BOT_TOKEN.
    discord_bot_token: str = field(default="", repr=False)
    discord_guild_id: int = DISCORD_GUILD_ID
    discord_bot_prefix: str = "o!"
    # Run the bot inside the API process (for hosts with a single start command). Otherwise: python discord_bot.py
    run_discord_bot: bool = False

    @property
    def dev_mode(self) -> bool:
        return self.mode == MODE_DEVELOPMENT

    @property
    def requires_auth(self) -> bool:
        return self.mode == MODE_PRODUCTION

    @classmethod
    def from_env(cls) -> "Settings":
        env = _environment()
        data_dir = Path(env.get("ORBIT_DATA_DIR", BASE_DIR / "data"))
        mode = env.get("ORBIT_MODE", "").strip().lower()
        if not mode:
            # Compatibility with the first prototype: ORBIT_DEV_MODE=0 meant read-only.
            mode = MODE_DEVELOPMENT if _bool(env.get("ORBIT_DEV_MODE"), True) else MODE_READ_ONLY
        if mode not in MODES:
            raise ValueError(f"ORBIT_MODE must be one of {', '.join(MODES)}, not {mode!r}")
        origins = env.get("ORBIT_CORS_ORIGINS", "")
        return cls(
            database_path=Path(env.get("ORBIT_DATABASE_PATH", data_dir / "orbit.sqlite3")),
            storage_dir=Path(env.get("ORBIT_STORAGE_DIR", BASE_DIR / "storage" / "cosmetics")),
            max_upload_bytes=_int(env, "ORBIT_MAX_UPLOAD_BYTES", 1024 * 1024),
            mode=mode,
            trust_proxy_headers=_bool(env.get("ORBIT_TRUST_PROXY_HEADERS"), True),
            cors_origins=tuple(origin.strip().rstrip("/") for origin in origins.split(",") if origin.strip()),
            session_ttl_seconds=_int(env, "ORBIT_SESSION_HOURS", 24) * 3600,
            uploads_per_player_per_day=_int(env, "ORBIT_UPLOADS_PER_PLAYER_PER_DAY", 20),
            max_storage_bytes=_int(env, "ORBIT_MAX_STORAGE_MB", 1024) * 1024 * 1024,
            requests_per_minute=_int(env, "ORBIT_REQUESTS_PER_MINUTE", 240),
            auth_requests_per_minute=_int(env, "ORBIT_AUTH_REQUESTS_PER_MINUTE", 10),
            discord_webhook_url=env.get("ORBIT_DISCORD_WEBHOOK_URL", "").strip(),
            report_retention_days=_int(env, "ORBIT_REPORT_RETENTION_DAYS", 7),
            report_alert_threshold=_int(env, "ORBIT_REPORT_ALERT_THRESHOLD", 3),
            report_ban_threshold=_int(env, "ORBIT_REPORT_BAN_THRESHOLD", 6),
            report_ban_days=_int(env, "ORBIT_REPORT_BAN_DAYS", 7),
            reports_per_player_per_day=_int(env, "ORBIT_REPORTS_PER_PLAYER_PER_DAY", 20),
            discord_bot_token=(env.get("ORBIT_DISCORD_BOT_TOKEN") or env.get("TOKEN") or "").strip(),
            discord_guild_id=_int(env, "ORBIT_DISCORD_GUILD_ID", DISCORD_GUILD_ID),
            discord_bot_prefix=env.get("ORBIT_DISCORD_BOT_PREFIX", "o!").strip() or "o!",
            run_discord_bot=_bool(env.get("ORBIT_DISCORD_BOT"), False),
        )

"""Who is making a request, and whether they may write.

Production authentication uses Minecraft's own session check, the same one online-mode servers use:

1. The client asks for a one-time server ID (POST /v1/auth/challenge).
2. The game tells Mojang's session server "this account joins server ID X", using the player's own login.
   The Microsoft/Mojang access token goes only to Mojang; this backend never sees it.
3. The client sends its player name and the server ID (POST /v1/auth/session). The backend asks Mojang
   "has this player joined server ID X?" and, if Mojang confirms, issues a random session token.

Only the SHA-256 of session tokens is stored, so a copy of the database cannot be used to impersonate anyone.
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import logging
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

from fastapi import Request

import models
from errors import ApiError

logger = logging.getLogger("orbit.auth")

LOOPBACK = frozenset({"127.0.0.1", "::1", "localhost"})
PROXY_HEADERS = ("cf-connecting-ip", "x-forwarded-for", "x-real-ip", "forwarded")


def peer_address(request: Request) -> str | None:
    return request.client.host if request.client else None


def client_ip(request: Request) -> str:
    """The visitor's address. Proxy headers are only believed when the request comes from this machine."""
    peer = peer_address(request) or "unknown"
    settings = request.app.state.settings
    if settings.trust_proxy_headers and peer in LOOPBACK:
        for header in ("cf-connecting-ip", "x-real-ip"):
            value = request.headers.get(header, "").strip()
            if _valid_ip(value):
                return value
        forwarded = request.headers.get("x-forwarded-for", "").split(",")[0].strip()
        if _valid_ip(forwarded):
            return forwarded
    return peer


def _valid_ip(value: str) -> bool:
    try:
        ipaddress.ip_address(value)
        return True
    except ValueError:
        return False


def is_local_request(request: Request) -> bool:
    """True only for requests made on this computer, not relayed by a tunnel or proxy running on it."""
    peer = peer_address(request)
    if peer not in request.app.state.settings.dev_hosts:
        return False
    # A tunnel or reverse proxy on this machine also connects from 127.0.0.1, but adds these headers.
    return not any(header in request.headers for header in PROXY_HEADERS)


# ---------------------------------------------------------------- rate limiting


class RateLimiter:
    """Per-key fixed one-minute windows, kept in memory. Good enough for a single backend process."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._windows: dict[str, tuple[int, int]] = {}

    def allow(self, key: str, per_minute: int, now: float | None = None) -> bool:
        minute = int((now if now is not None else time.time()) // 60)
        with self._lock:
            if len(self._windows) > 50_000:
                self._windows = {k: v for k, v in self._windows.items() if v[0] == minute}
            window, count = self._windows.get(key, (minute, 0))
            if window != minute:
                window, count = minute, 0
            if count >= per_minute:
                return False
            self._windows[key] = (window, count + 1)
            return True


# ---------------------------------------------------------------- writers


@dataclass(frozen=True)
class Writer:
    """Someone allowed to write. In development there is no player identity (player_uuid is None)."""

    player_uuid: str | None
    player_name: str | None
    # Set when the player is banned and the caller allowed banned players (they may wear featured cosmetics).
    ban: models.Ban | None = None


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def bearer_token(request: Request) -> str | None:
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        return None
    return token.strip()


def current_session(request: Request, connection: sqlite3.Connection) -> models.Session | None:
    token = bearer_token(request)
    if token is None or len(token) > 200:
        return None
    return models.get_session(connection, token_hash(token), time.time())


def blocked_error(ban: models.Ban) -> ApiError:
    until = "" if ban.expires_at is None else time.strftime(" until %Y-%m-%d %H:%M UTC", time.gmtime(ban.expires_at))
    return ApiError(
        403,
        "blocked",
        f"This player is banned from Orbit Cloud{until}. Featured cosmetics can still be worn.",
        {"expires_at": ban.expires_at, "reason": ban.reason},
    )


def require_writer(request: Request, connection: sqlite3.Connection, allow_banned: bool = False) -> Writer:
    """Checks that the request may change data, according to the server mode.

    With ``allow_banned``, banned players pass with ``Writer.ban`` set, and the caller restricts what they may do.
    """
    settings = request.app.state.settings
    if settings.mode == "read-only":
        raise ApiError(403, "read_only", "This server is read-only.")
    if settings.dev_mode:
        if not is_local_request(request):
            logger.warning("Refused development-only request from %s", client_ip(request))
            raise ApiError(403, "development_only", "This endpoint only accepts requests from this computer.")
        return Writer(None, None)
    session = current_session(request, connection)
    if session is None:
        raise ApiError(401, "not_authenticated", "Sign in first: POST /v1/auth/challenge, then POST /v1/auth/session.")
    ban = models.get_ban(connection, session.player_uuid, time.time())
    if ban is not None and not allow_banned:
        raise blocked_error(ban)
    return Writer(session.player_uuid, session.player_name, ban)


# ---------------------------------------------------------------- Mojang


class MojangUnavailable(Exception):
    pass


def mojang_has_joined(url: str, username: str, server_id: str, timeout: float = 5.0) -> tuple[str, str] | None:
    """Asks Mojang whether this player just joined server_id. Returns (uuid with dashes, name) or None.

    Raises MojangUnavailable when Mojang cannot be reached or answers unexpectedly.
    """
    query = urllib.parse.urlencode({"username": username, "serverId": server_id})
    request = urllib.request.Request(f"{url}?{query}", headers={"User-Agent": "OrbitCosmeticsBackend"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status == 204:
                return None
            body = response.read(64 * 1024)
    except urllib.error.HTTPError as error:
        if error.code in (204, 403, 404):
            return None
        raise MojangUnavailable(f"HTTP {error.code}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise MojangUnavailable(str(error)) from None
    if not body:
        return None
    try:
        data = json.loads(body)
        raw_id = str(data["id"]).lower()
        name = str(data["name"])
    except (ValueError, KeyError, TypeError):
        raise MojangUnavailable("unexpected response") from None
    if len(raw_id) != 32 or any(c not in "0123456789abcdef" for c in raw_id):
        raise MojangUnavailable("unexpected profile id")
    uuid = f"{raw_id[:8]}-{raw_id[8:12]}-{raw_id[12:16]}-{raw_id[16:20]}-{raw_id[20:]}"
    return uuid, name

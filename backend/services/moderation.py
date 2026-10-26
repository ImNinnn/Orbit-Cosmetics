"""Moderation actions shared by the Discord bot: finding players, bans and reports.

Everything here works on a database connection and returns plain data, so it can be tested without Discord.
"""

from __future__ import annotations

import base64
import binascii
import json
import re
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid as uuid_module
from dataclasses import dataclass

import models
from services.equip import InvalidEquip, worn_cosmetics

PLAYER_NAME = re.compile(r"^[A-Za-z0-9_]{1,16}$")
MOJANG_PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/"
DURATION = re.compile(r"^(\d{1,4})([hdw]?)$")
DAY = 86400


@dataclass(frozen=True)
class Player:
    uuid: str
    name: str | None


@dataclass(frozen=True)
class PlayerInfo:
    player: Player
    ban: models.Ban | None
    reporters: int
    reasons: dict[str, int]
    cosmetics: list[tuple[str, str]]


class ModerationError(ValueError):
    """A problem to show to the moderator as is."""


def parse_uuid(text: str) -> str | None:
    try:
        return str(uuid_module.UUID(text.strip()))
    except ValueError:
        return None


def known_name(connection: sqlite3.Connection, player_uuid: str) -> str | None:
    """The latest name this backend saw for a player."""
    for query in (
        "SELECT player_name FROM presence WHERE player_uuid = ?",
        "SELECT player_name FROM sessions WHERE player_uuid = ? ORDER BY created_at DESC LIMIT 1",
        "SELECT player_name FROM reports WHERE player_uuid = ? ORDER BY created_at DESC LIMIT 1",
    ):
        row = connection.execute(query, (player_uuid,)).fetchone()
        if row:
            return row[0]
    return None


def find_local(connection: sqlite3.Connection, text: str) -> Player | None:
    """A player by UUID, or by a name this backend has seen (presence, sign-ins, reports)."""
    player_uuid = parse_uuid(text)
    if player_uuid is not None:
        return Player(player_uuid, known_name(connection, player_uuid))
    if not PLAYER_NAME.match(text):
        return None
    for query in (
        "SELECT player_uuid, player_name FROM presence WHERE player_name = ? COLLATE NOCASE",
        "SELECT player_uuid, player_name FROM sessions WHERE player_name = ? COLLATE NOCASE ORDER BY created_at DESC LIMIT 1",
        "SELECT player_uuid, player_name FROM reports WHERE player_name = ? COLLATE NOCASE ORDER BY created_at DESC LIMIT 1",
    ):
        row = connection.execute(query, (text,)).fetchone()
        if row:
            return Player(row[0], row[1])
    return None


def mojang_lookup(name: str, timeout: float = 5.0) -> Player | None:
    """Looks a Minecraft name up with Mojang (for players this backend has never seen)."""
    if not PLAYER_NAME.match(name):
        return None
    request = urllib.request.Request(MOJANG_PROFILE_URL + urllib.parse.quote(name), headers={"User-Agent": "OrbitCosmeticsBackend"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status != 200:
                return None
            data = json.loads(response.read(16 * 1024))
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        return None
    player_uuid = parse_uuid(str(data.get("id", "")))
    return Player(player_uuid, str(data.get("name", name))[:16]) if player_uuid else None


def parse_duration(text: str) -> float | None:
    """Seconds for "7", "7d", "12h" or "2w"; None for "perm"/"permanent"/"forever"."""
    value = text.strip().lower()
    if value in ("perm", "permanent", "forever"):
        return None
    match = DURATION.match(value)
    if not match or int(match.group(1)) == 0:
        raise ModerationError(f"`{text}` is not a duration. Use for example `7d`, `12h`, `2w` or `perm`.")
    unit = {"h": 3600, "d": DAY, "w": 7 * DAY, "": DAY}[match.group(2)]
    seconds = int(match.group(1)) * unit
    if seconds > 3650 * DAY:
        raise ModerationError("That is more than ten years; use `perm` for a permanent ban.")
    return float(seconds)


def ban(connection: sqlite3.Connection, player: Player, seconds: float | None, reason: str, moderator: str, now: float | None = None) -> models.Ban:
    now = time.time() if now is None else now
    expires = now + seconds if seconds is not None else None
    text = " ".join(reason.split())[:200] or "No reason given"
    with connection:
        models.block_player(connection, player.uuid, f"{text} (by {moderator})"[:250], now, expires, source="discord")
        # Their cosmetics disappear at once; their client then shares featured cosmetics only.
        models.delete_presence(connection, player.uuid)
    ban_record = models.get_ban(connection, player.uuid, now)
    assert ban_record is not None
    return ban_record


def unban(connection: sqlite3.Connection, player: Player) -> bool:
    with connection:
        return models.unblock_player(connection, player.uuid)


def bans(connection: sqlite3.Connection) -> list[tuple[models.Ban, str | None]]:
    with connection:
        current = models.blocked_players(connection)
    return [(entry, known_name(connection, entry.player_uuid)) for entry in current]


def reported(connection: sqlite3.Connection, retention_days: int) -> list[tuple[str, str, int, float]]:
    with connection:
        models.prune_reports(connection, time.time() - retention_days * DAY)
    return models.reported_players(connection)


def reports_on(connection: sqlite3.Connection, player: Player, retention_days: int) -> list[models.Report]:
    with connection:
        models.prune_reports(connection, time.time() - retention_days * DAY)
    return models.reports_for(connection, player.uuid)


def clear_reports(connection: sqlite3.Connection, player: Player) -> int:
    with connection:
        return models.clear_reports(connection, player.uuid)


def info(connection: sqlite3.Connection, player: Player, retention_days: int) -> PlayerInfo:
    reports = reports_on(connection, player, retention_days)
    reasons: dict[str, int] = {}
    for report in reports:
        reasons[report.reason] = reasons.get(report.reason, 0) + 1
    cosmetics: list[tuple[str, str]] = []
    presence = models.get_presence(connection, player.uuid)
    if presence is not None:
        try:
            cosmetics = [(c.sha256, c.name) for c in worn_cosmetics(base64.b64decode(presence.equip, validate=True))]
        except (InvalidEquip, binascii.Error, ValueError):
            cosmetics = []
    return PlayerInfo(
        player=player,
        ban=models.get_ban(connection, player.uuid, time.time()),
        reporters=len({report.reporter_uuid for report in reports}),
        reasons=reasons,
        cosmetics=cosmetics,
    )

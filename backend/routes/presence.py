"""Who wears what. Orbit clients use this on Minecraft servers that do not run the Orbit server mod or plugin."""

from __future__ import annotations

import base64
import binascii
import logging
import re
import sqlite3
import time

from fastapi import APIRouter, Depends, Request

import config
import models
from database import get_connection
from errors import ApiError
import auth
from schemas import ErrorResponse, PresenceEntry, PresenceList, PresenceQuery, PresenceStored, PresenceUpdate
from services.equip import InvalidEquip, worn_cosmetics

logger = logging.getLogger("orbit.presence")
router = APIRouter(prefix=f"{config.API_PREFIX}/presence", tags=["presence"])

# Clients refresh every 30 seconds; entries older than this are treated as offline.
PRESENCE_TTL_SECONDS = 90
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
PLAYER_NAME = re.compile(r"^[A-Za-z0-9_]{1,16}$")
# Matches the Orbit protocol's limit for one serverbound message.
MAX_EQUIP_BYTES = 32_000


def _player_uuid(value: str) -> str:
    normalized = value.strip().lower()
    if not UUID.match(normalized):
        raise ApiError(400, "invalid_uuid", "Player IDs must be UUIDs like 069a79f4-44e9-4726-a5be-fca90e38aaf5.")
    return normalized


@router.put(
    "/{player_uuid}",
    response_model=PresenceStored,
    summary="Report what a player wears (the player themself in production; this computer in development)",
    responses={400: {"model": ErrorResponse}, 401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}},
)
def update_presence(
    request: Request,
    player_uuid: str,
    update: PresenceUpdate,
    connection: sqlite3.Connection = Depends(get_connection),
) -> PresenceStored:
    writer = auth.require_writer(request, connection, allow_banned=True)
    player = _player_uuid(player_uuid)
    if not PLAYER_NAME.match(update.player_name):
        raise ApiError(400, "invalid_name", "Player names are 1-16 letters, digits or underscores.")
    name = update.player_name
    if writer.player_uuid is not None:
        # Production: players can only publish for themselves, under their verified name.
        if player != writer.player_uuid:
            raise ApiError(403, "not_your_player", "You can only report your own presence.")
        name = writer.player_name
    try:
        equip = base64.b64decode(update.equip, validate=True)
    except (binascii.Error, ValueError):
        raise ApiError(400, "invalid_equip", "equip must be base64.") from None
    if not equip or len(equip) > MAX_EQUIP_BYTES:
        raise ApiError(400, "invalid_equip", f"equip must decode to 1-{MAX_EQUIP_BYTES} bytes.")
    now = time.time()
    # Banned players may only wear featured cosmetics. (Development mode has no accounts; bans apply to the UUID.)
    ban = writer.ban if writer.player_uuid is not None else models.get_ban(connection, player, now)
    if ban is not None:
        try:
            hashes = [cosmetic.sha256 for cosmetic in worn_cosmetics(equip)]
        except InvalidEquip:
            raise ApiError(400, "invalid_equip", "equip is not a valid Orbit Equip message.") from None
        if set(hashes) - models.featured_hashes(connection, hashes):
            raise auth.blocked_error(ban)
    with connection:
        models.prune_presence(connection, now - PRESENCE_TTL_SECONDS)
        models.upsert_presence(connection, models.Presence(player, name, update.protocol, update.equip, now))
    return PresenceStored(player_uuid=player, ttl_seconds=PRESENCE_TTL_SECONDS)


@router.post("/query", response_model=PresenceList, summary="What the given players currently wear")
def query_presence(query: PresenceQuery, connection: sqlite3.Connection = Depends(get_connection)) -> PresenceList:
    players = sorted({_player_uuid(value) for value in query.players})
    names = sorted({name for name in query.names if PLAYER_NAME.match(name)})
    entries = models.fresh_presence(connection, players, time.time() - PRESENCE_TTL_SECONDS, names)
    return PresenceList(players=[PresenceEntry(**entry.__dict__) for entry in entries], ttl_seconds=PRESENCE_TTL_SECONDS)


@router.delete(
    "/{player_uuid}",
    response_model=PresenceStored,
    summary="Forget a player's presence, e.g. when they leave a server",
    responses={401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}, 404: {"model": ErrorResponse}},
)
def delete_presence(request: Request, player_uuid: str, connection: sqlite3.Connection = Depends(get_connection)) -> PresenceStored:
    writer = auth.require_writer(request, connection)
    player = _player_uuid(player_uuid)
    if writer.player_uuid is not None and player != writer.player_uuid:
        raise ApiError(403, "not_your_player", "You can only remove your own presence.")
    with connection:
        if not models.delete_presence(connection, player):
            raise ApiError(404, "not_found", "No presence is stored for this player.")
    return PresenceStored(player_uuid=player, ttl_seconds=PRESENCE_TTL_SECONDS)

"""Player sign-in through Mojang's session check (see auth.py for how it works)."""

from __future__ import annotations

import logging
import re
import secrets
import sqlite3
import time

from fastapi import APIRouter, Depends, Request

import auth
import config
import models
from database import get_connection
from errors import ApiError
from schemas import ChallengeResponse, ErrorResponse, SessionRequest, SessionResponse

logger = logging.getLogger("orbit.auth")
router = APIRouter(prefix=f"{config.API_PREFIX}/auth", tags=["auth"])

CHALLENGE_TTL_SECONDS = 120
SERVER_ID = re.compile(r"^[0-9a-f]{40}$")
PLAYER_NAME = re.compile(r"^[A-Za-z0-9_]{1,16}$")


@router.post("/challenge", response_model=ChallengeResponse, summary="Start signing in: get a one-time server ID")
def challenge(connection: sqlite3.Connection = Depends(get_connection)) -> ChallengeResponse:
    server_id = secrets.token_hex(20)
    now = time.time()
    with connection:
        models.add_challenge(connection, server_id, now + CHALLENGE_TTL_SECONDS, now)
    return ChallengeResponse(server_id=server_id, expires_in=CHALLENGE_TTL_SECONDS)


@router.post(
    "/session",
    response_model=SessionResponse,
    summary="Finish signing in after the game called Mojang's join endpoint with the server ID",
    responses={401: {"model": ErrorResponse}, 502: {"model": ErrorResponse}},
)
def create_session(request: Request, body: SessionRequest, connection: sqlite3.Connection = Depends(get_connection)) -> SessionResponse:
    settings = request.app.state.settings
    if not PLAYER_NAME.match(body.username) or not SERVER_ID.match(body.server_id):
        raise ApiError(400, "invalid_request", "username must be a Minecraft name and server_id a challenge from /v1/auth/challenge.")
    now = time.time()
    with connection:
        valid = models.take_challenge(connection, body.server_id, now)
    if not valid:
        raise ApiError(401, "invalid_challenge", "Unknown or expired server_id; request a new challenge.")
    try:
        profile = auth.mojang_has_joined(settings.mojang_has_joined_url, body.username, body.server_id)
    except auth.MojangUnavailable as error:
        logger.warning("Mojang session check failed: %s", error)
        raise ApiError(502, "mojang_unavailable", "Mojang's session server could not be reached; try again later.") from None
    if profile is None or profile[1].lower() != body.username.lower():
        logger.info("Sign-in refused for %s from %s", body.username, auth.client_ip(request))
        raise ApiError(401, "not_authenticated", "Mojang did not confirm this player. Make sure the game is logged in.")
    player_uuid, player_name = profile
    token = secrets.token_urlsafe(32)
    session = models.Session(player_uuid, player_name, now + settings.session_ttl_seconds)
    with connection:
        models.add_session(connection, auth.token_hash(token), session, now)
    logger.info("Signed in %s (%s)", player_name, player_uuid)
    return _response(connection, session, token)


@router.get("/me", response_model=SessionResponse, summary="The player of the current session", responses={401: {"model": ErrorResponse}})
def me(request: Request, connection: sqlite3.Connection = Depends(get_connection)) -> SessionResponse:
    session = auth.current_session(request, connection)
    if session is None:
        raise ApiError(401, "not_authenticated", "No valid session token.")
    return _response(connection, session, None)


def _response(connection: sqlite3.Connection, session: models.Session, token: str | None) -> SessionResponse:
    ban = models.get_ban(connection, session.player_uuid, time.time())
    return SessionResponse(
        token=token,
        player_uuid=session.player_uuid,
        player_name=session.player_name,
        expires_at=session.expires_at,
        banned=ban is not None,
        banned_until=ban.expires_at if ban else None,
    )


@router.delete("/session", summary="Sign out (invalidate the current token)", responses={401: {"model": ErrorResponse}})
def delete_session(request: Request, connection: sqlite3.Connection = Depends(get_connection)) -> dict:
    token = auth.bearer_token(request)
    with connection:
        if token is None or not models.delete_session(connection, auth.token_hash(token)):
            raise ApiError(401, "not_authenticated", "No valid session token.")
    return {"signed_out": True}

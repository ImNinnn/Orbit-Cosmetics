"""Player reports from the in-game Players menu.

Reports are kept for ``report_retention_days`` (7), then dropped. Each reporter counts once per reported player.
When ``report_alert_threshold`` (3) different players reported someone, moderators get a Discord message; at
``report_ban_threshold`` (6) the player is banned from Orbit Cloud for ``report_ban_days`` (7): no uploads, and
only featured cosmetics are shared.
"""

from __future__ import annotations

import base64
import binascii
import json
import logging
import re
import sqlite3
import time
from collections import Counter

from fastapi import APIRouter, Depends, Request

import auth
import config
import models
from database import get_connection
from errors import ApiError
from schemas import ErrorResponse, ReportRequest, ReportResponse
from services.discord import ReportAlert, ReportedCosmetic
from services.equip import InvalidEquip, worn_cosmetics

logger = logging.getLogger("orbit.reports")
router = APIRouter(prefix=f"{config.API_PREFIX}/reports", tags=["reports"])

REASONS = ("inappropriate", "offensive", "lag", "impersonation", "other")
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
PLAYER_NAME = re.compile(r"^[A-Za-z0-9_]{1,16}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
DAY = 86400


def _uuid(value: str | None, field: str) -> str:
    normalized = (value or "").strip().lower()
    if not UUID.match(normalized):
        raise ApiError(400, "invalid_uuid", f"{field} must be a UUID like 069a79f4-44e9-4726-a5be-fca90e38aaf5.")
    return normalized


def _clean_note(note: str) -> str:
    # Printable text on one line; Discord formatting is escaped when the alert is built.
    return " ".join("".join(ch for ch in note if ch.isprintable()).split())[:200]


def _reporter(request: Request, connection: sqlite3.Connection, body: ReportRequest) -> str:
    settings = request.app.state.settings
    if settings.mode == config.MODE_READ_ONLY:
        raise ApiError(403, "read_only", "This server is read-only.")
    if settings.dev_mode:
        if not auth.is_local_request(request):
            raise ApiError(403, "development_only", "This endpoint only accepts requests from this computer.")
        return _uuid(body.reporter_uuid, "reporter_uuid")
    session = auth.current_session(request, connection)
    if session is None:
        raise ApiError(401, "not_authenticated", "Sign in first: POST /v1/auth/challenge, then POST /v1/auth/session.")
    ban = models.get_ban(connection, session.player_uuid, time.time())
    if ban is not None:
        raise auth.blocked_error(ban)
    return session.player_uuid


@router.post(
    "",
    response_model=ReportResponse,
    status_code=201,
    summary="Report a player's cosmetics",
    responses={400: {"model": ErrorResponse}, 401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}, 429: {"model": ErrorResponse}},
)
def create_report(request: Request, body: ReportRequest, connection: sqlite3.Connection = Depends(get_connection)) -> ReportResponse:
    settings: config.Settings = request.app.state.settings
    reporter = _reporter(request, connection, body)
    target = _uuid(body.player_uuid, "player_uuid")
    if target == reporter:
        raise ApiError(400, "cannot_report_self", "You cannot report yourself.")
    if not PLAYER_NAME.match(body.player_name):
        raise ApiError(400, "invalid_name", "Player names are 1-16 letters, digits or underscores.")
    reason = body.reason.strip().lower()
    if reason not in REASONS:
        raise ApiError(400, "invalid_reason", f"reason must be one of: {', '.join(REASONS)}.")
    hashes = list(dict.fromkeys(value.lower() for value in body.cosmetics if SHA256.match(value.lower())))[:16]

    now = time.time()
    with connection:
        models.prune_reports(connection, now - settings.report_retention_days * DAY)
        if models.reports_by_since(connection, reporter, now - DAY) >= settings.reports_per_player_per_day:
            raise ApiError(429, "report_limit", f"At most {settings.reports_per_player_per_day} reports per day.")
        new = models.add_report(
            connection,
            models.Report(target, body.player_name, reporter, reason, _clean_note(body.note), json.dumps(hashes), now),
        )
        alert = _evaluate(connection, settings, target, now)
    logger.info("Report on %s (%s) for %s%s", body.player_name, target, reason, "" if new else " (updated)")
    if alert is not None:
        request.app.state.notifier.send(alert)
    return ReportResponse(received=True, new=new)


def _evaluate(connection: sqlite3.Connection, settings: config.Settings, target: str, now: float) -> ReportAlert | None:
    """Alerts moderators or bans the player once enough different players reported them."""
    reports = models.reports_for(connection, target)
    reporters = len({report.reporter_uuid for report in reports})
    banned_days = None
    if reporters >= settings.report_ban_threshold and models.get_ban(connection, target, now) is None and models.last_action(connection, target, "ban") is None:
        models.block_player(
            connection, target, f"{reporters} reports", now, now + settings.report_ban_days * DAY, source="reports"
        )
        # Hide what they wear right away; their client then shares featured cosmetics only.
        models.delete_presence(connection, target)
        models.record_action(connection, target, "ban", now)
        banned_days = settings.report_ban_days
        logger.warning("Banned %s for %d days after %d reports", target, settings.report_ban_days, reporters)
    elif reporters >= settings.report_alert_threshold and models.last_action(connection, target, "alert") is None:
        models.record_action(connection, target, "alert", now)
        logger.warning("%s has been reported by %d players; alerting moderators", target, reporters)
    else:
        return None
    return _alert(connection, target, reports, reporters, banned_days)


def _alert(connection: sqlite3.Connection, target: str, reports: list[models.Report], reporters: int, banned_days: int | None) -> ReportAlert:
    presence = models.get_presence(connection, target)
    worn: list[tuple[str, str]] = []
    if presence is not None:
        try:
            worn = [(c.sha256, c.name) for c in worn_cosmetics(base64.b64decode(presence.equip, validate=True))]
        except (InvalidEquip, binascii.Error, ValueError):
            worn = []
    if not worn:
        # Not online through Orbit Cloud (for example on an Orbit server): what the reporters saw.
        seen: dict[str, str] = {}
        for report in reports:
            for sha in json.loads(report.cosmetics):
                seen.setdefault(sha, "")
        worn = list(seen.items())
    known = models.names_by_hash(connection, [sha for sha, _ in worn])
    cosmetics = [
        ReportedCosmetic(sha, name or (known[sha].display_name if sha in known else "Unknown"), known[sha].id if sha in known else None)
        for sha, name in worn
    ]
    name = presence.player_name if presence is not None else reports[-1].player_name
    return ReportAlert(
        player_uuid=target,
        player_name=name,
        reporters=reporters,
        reasons=dict(Counter(report.reason for report in reports)),
        notes=[report.note for report in reports if report.note],
        cosmetics=cosmetics,
        banned_days=banned_days,
    )

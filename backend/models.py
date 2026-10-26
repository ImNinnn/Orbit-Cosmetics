"""Database schema and queries for cosmetic metadata. All queries are parameterized."""

from __future__ import annotations

import sqlite3
import time
from dataclasses import dataclass

SCHEMA_VERSION = 4

# Validation states. Only APPROVED cosmetics can be downloaded. PENDING and REJECTED are reserved for the
# review workflow of later versions; the prototype only stores cosmetics that passed validation.
STATUS_APPROVED = "approved"
STATUS_PENDING = "pending"
STATUS_REJECTED = "rejected"

_SCHEMA = f"""
CREATE TABLE IF NOT EXISTS cosmetics (
    id                TEXT PRIMARY KEY,
    display_name      TEXT NOT NULL,
    original_filename TEXT NOT NULL,
    stored_filename   TEXT NOT NULL UNIQUE,
    size_bytes        INTEGER NOT NULL CHECK (size_bytes > 0),
    sha256            TEXT NOT NULL UNIQUE,
    uploaded_at       TEXT NOT NULL,
    validation_status TEXT NOT NULL CHECK (validation_status IN ('{STATUS_APPROVED}', '{STATUS_PENDING}', '{STATUS_REJECTED}')),
    format_version    TEXT,
    model_format      TEXT,
    element_count     INTEGER NOT NULL DEFAULT 0,
    texture_count     INTEGER NOT NULL DEFAULT 0,
    animation_count   INTEGER NOT NULL DEFAULT 0,
    owner_uuid        TEXT,
    -- Featured: checked by hand and safe to use; listed in the in-game catalog. Set with manage.py.
    featured          INTEGER NOT NULL DEFAULT 0,
    featured_at       TEXT,
    author            TEXT,
    description       TEXT
);
CREATE INDEX IF NOT EXISTS cosmetics_uploaded_at ON cosmetics (uploaded_at);

-- What each player currently wears, reported by their Orbit client. Rows expire unless refreshed.
CREATE TABLE IF NOT EXISTS presence (
    player_uuid TEXT PRIMARY KEY,
    player_name TEXT NOT NULL,
    protocol    INTEGER NOT NULL,
    equip       TEXT NOT NULL,
    updated_at  REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS presence_updated_at ON presence (updated_at);
CREATE INDEX IF NOT EXISTS presence_name ON presence (player_name COLLATE NOCASE);

-- Player sessions created after Mojang confirmed the player. Only the SHA-256 of each token is stored.
CREATE TABLE IF NOT EXISTS sessions (
    token_hash  TEXT PRIMARY KEY,
    player_uuid TEXT NOT NULL,
    player_name TEXT NOT NULL,
    created_at  REAL NOT NULL,
    expires_at  REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_expires_at ON sessions (expires_at);

-- One-time server IDs handed out for the Mojang session check.
CREATE TABLE IF NOT EXISTS auth_challenges (
    server_id  TEXT PRIMARY KEY,
    expires_at REAL NOT NULL
);

-- Banned players: no uploads, and only featured cosmetics in their presence. expires_at NULL = permanent.
CREATE TABLE IF NOT EXISTS blocked_players (
    player_uuid TEXT PRIMARY KEY,
    reason      TEXT NOT NULL DEFAULT '',
    blocked_at  REAL NOT NULL,
    expires_at  REAL,
    source      TEXT NOT NULL DEFAULT 'manual'
);

-- Player reports. One per reporter and reported player; dropped after the retention time.
CREATE TABLE IF NOT EXISTS reports (
    id            INTEGER PRIMARY KEY,
    player_uuid   TEXT NOT NULL,
    player_name   TEXT NOT NULL,
    reporter_uuid TEXT NOT NULL,
    reason        TEXT NOT NULL,
    note          TEXT NOT NULL DEFAULT '',
    cosmetics     TEXT NOT NULL DEFAULT '[]',
    created_at    REAL NOT NULL,
    UNIQUE (player_uuid, reporter_uuid)
);
CREATE INDEX IF NOT EXISTS reports_created_at ON reports (created_at);
CREATE INDEX IF NOT EXISTS reports_reporter ON reports (reporter_uuid, created_at);

-- When moderators were last alerted about a player ("alert") or the player was banned automatically ("ban").
CREATE TABLE IF NOT EXISTS report_actions (
    player_uuid TEXT NOT NULL,
    kind        TEXT NOT NULL,
    at          REAL NOT NULL,
    PRIMARY KEY (player_uuid, kind)
);
"""


@dataclass(frozen=True)
class Cosmetic:
    id: str
    display_name: str
    original_filename: str
    stored_filename: str
    size_bytes: int
    sha256: str
    uploaded_at: str
    validation_status: str
    format_version: str | None
    model_format: str | None
    element_count: int
    texture_count: int
    animation_count: int
    owner_uuid: str | None = None
    featured: int = 0
    featured_at: str | None = None
    author: str | None = None
    description: str | None = None

    @classmethod
    def from_row(cls, row: sqlite3.Row) -> "Cosmetic":
        return cls(**{key: row[key] for key in row.keys()})


def create_schema(connection: sqlite3.Connection) -> None:
    # Every statement is "IF NOT EXISTS", so older databases gain new tables without losing data.
    added_columns = {
        "cosmetics": {
            "owner_uuid": "TEXT",
            "featured": "INTEGER NOT NULL DEFAULT 0",
            "featured_at": "TEXT",
            "author": "TEXT",
            "description": "TEXT",
        },
        "blocked_players": {"expires_at": "REAL", "source": "TEXT NOT NULL DEFAULT 'manual'"},
    }
    for table, wanted in added_columns.items():
        columns = {row[1] for row in connection.execute(f"PRAGMA table_info({table})")}
        for column, definition in wanted.items():
            if columns and column not in columns:
                connection.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")
    connection.executescript(_SCHEMA)
    connection.execute("CREATE INDEX IF NOT EXISTS cosmetics_owner ON cosmetics (owner_uuid, uploaded_at)")
    connection.execute("CREATE INDEX IF NOT EXISTS cosmetics_featured ON cosmetics (featured, featured_at)")
    version = connection.execute("PRAGMA user_version").fetchone()[0]
    if version < SCHEMA_VERSION:
        connection.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")


def insert(connection: sqlite3.Connection, cosmetic: Cosmetic) -> None:
    connection.execute(
        """
        INSERT INTO cosmetics (id, display_name, original_filename, stored_filename, size_bytes, sha256, uploaded_at,
                               validation_status, format_version, model_format, element_count, texture_count, animation_count,
                               owner_uuid, featured, featured_at, author, description)
        VALUES (:id, :display_name, :original_filename, :stored_filename, :size_bytes, :sha256, :uploaded_at,
                :validation_status, :format_version, :model_format, :element_count, :texture_count, :animation_count,
                :owner_uuid, :featured, :featured_at, :author, :description)
        """,
        cosmetic.__dict__,
    )


def get(connection: sqlite3.Connection, cosmetic_id: str) -> Cosmetic | None:
    row = connection.execute("SELECT * FROM cosmetics WHERE id = ?", (cosmetic_id,)).fetchone()
    return Cosmetic.from_row(row) if row else None


def find_by_sha256(connection: sqlite3.Connection, sha256: str) -> Cosmetic | None:
    row = connection.execute("SELECT * FROM cosmetics WHERE sha256 = ?", (sha256,)).fetchone()
    return Cosmetic.from_row(row) if row else None


def list_all(connection: sqlite3.Connection, limit: int, offset: int) -> list[Cosmetic]:
    rows = connection.execute(
        "SELECT * FROM cosmetics ORDER BY uploaded_at DESC, id LIMIT ? OFFSET ?", (limit, offset)
    ).fetchall()
    return [Cosmetic.from_row(row) for row in rows]


def count(connection: sqlite3.Connection) -> int:
    return connection.execute("SELECT COUNT(*) FROM cosmetics").fetchone()[0]


def delete(connection: sqlite3.Connection, cosmetic_id: str) -> bool:
    return connection.execute("DELETE FROM cosmetics WHERE id = ?", (cosmetic_id,)).rowcount > 0


@dataclass(frozen=True)
class Presence:
    player_uuid: str
    player_name: str
    protocol: int
    equip: str
    updated_at: float


def upsert_presence(connection: sqlite3.Connection, presence: Presence) -> None:
    connection.execute(
        """
        INSERT INTO presence (player_uuid, player_name, protocol, equip, updated_at)
        VALUES (:player_uuid, :player_name, :protocol, :equip, :updated_at)
        ON CONFLICT (player_uuid) DO UPDATE SET
            player_name = excluded.player_name, protocol = excluded.protocol,
            equip = excluded.equip, updated_at = excluded.updated_at
        """,
        presence.__dict__,
    )


def fresh_presence(connection: sqlite3.Connection, player_uuids: list[str], newer_than: float, player_names: list[str] = ()) -> list[Presence]:
    """Entries refreshed after newer_than for any of the UUIDs or (case-insensitive) names."""
    if not player_uuids and not player_names:
        return []
    uuids = ",".join("?" for _ in player_uuids) or "NULL"
    names = ",".join("?" for _ in player_names) or "NULL"
    rows = connection.execute(
        f"SELECT * FROM presence WHERE updated_at >= ? AND (player_uuid IN ({uuids}) OR lower(player_name) IN ({names}))"
        " ORDER BY player_uuid",
        (newer_than, *player_uuids, *(name.lower() for name in player_names)),
    ).fetchall()
    return [Presence(**{key: row[key] for key in row.keys()}) for row in rows]


def delete_presence(connection: sqlite3.Connection, player_uuid: str) -> bool:
    return connection.execute("DELETE FROM presence WHERE player_uuid = ?", (player_uuid,)).rowcount > 0


def prune_presence(connection: sqlite3.Connection, older_than: float) -> int:
    return connection.execute("DELETE FROM presence WHERE updated_at < ?", (older_than,)).rowcount


def total_size(connection: sqlite3.Connection) -> int:
    return connection.execute("SELECT COALESCE(SUM(size_bytes), 0) FROM cosmetics").fetchone()[0]


def uploads_since(connection: sqlite3.Connection, owner_uuid: str, since_iso: str) -> int:
    return connection.execute(
        "SELECT COUNT(*) FROM cosmetics WHERE owner_uuid = ? AND uploaded_at >= ?", (owner_uuid, since_iso)
    ).fetchone()[0]


# ---------------------------------------------------------------- sessions and moderation


@dataclass(frozen=True)
class Session:
    player_uuid: str
    player_name: str
    expires_at: float


def add_challenge(connection: sqlite3.Connection, server_id: str, expires_at: float, now: float) -> None:
    connection.execute("DELETE FROM auth_challenges WHERE expires_at < ?", (now,))
    connection.execute("INSERT INTO auth_challenges (server_id, expires_at) VALUES (?, ?)", (server_id, expires_at))


def take_challenge(connection: sqlite3.Connection, server_id: str, now: float) -> bool:
    """Consumes a challenge. Each one can be used once, before it expires."""
    removed = connection.execute(
        "DELETE FROM auth_challenges WHERE server_id = ? AND expires_at >= ?", (server_id, now)
    ).rowcount
    return removed > 0


def add_session(connection: sqlite3.Connection, token_hash: str, session: Session, now: float) -> None:
    connection.execute("DELETE FROM sessions WHERE expires_at < ?", (now,))
    connection.execute(
        "INSERT INTO sessions (token_hash, player_uuid, player_name, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
        (token_hash, session.player_uuid, session.player_name, now, session.expires_at),
    )


def get_session(connection: sqlite3.Connection, token_hash: str, now: float) -> Session | None:
    row = connection.execute(
        "SELECT player_uuid, player_name, expires_at FROM sessions WHERE token_hash = ? AND expires_at >= ?", (token_hash, now)
    ).fetchone()
    return Session(row[0], row[1], row[2]) if row else None


def delete_session(connection: sqlite3.Connection, token_hash: str) -> bool:
    return connection.execute("DELETE FROM sessions WHERE token_hash = ?", (token_hash,)).rowcount > 0


def delete_player_sessions(connection: sqlite3.Connection, player_uuid: str) -> int:
    return connection.execute("DELETE FROM sessions WHERE player_uuid = ?", (player_uuid,)).rowcount


@dataclass(frozen=True)
class Ban:
    player_uuid: str
    reason: str
    blocked_at: float
    expires_at: float | None
    source: str


def get_ban(connection: sqlite3.Connection, player_uuid: str, now: float) -> Ban | None:
    """The player's ban if it is in force at ``now``."""
    row = connection.execute(
        "SELECT player_uuid, reason, blocked_at, expires_at, source FROM blocked_players"
        " WHERE player_uuid = ? AND (expires_at IS NULL OR expires_at > ?)",
        (player_uuid, now),
    ).fetchone()
    return Ban(*row) if row else None


def is_blocked(connection: sqlite3.Connection, player_uuid: str, now: float | None = None) -> bool:
    return get_ban(connection, player_uuid, time.time() if now is None else now) is not None


def block_player(
    connection: sqlite3.Connection, player_uuid: str, reason: str, now: float, expires_at: float | None = None, source: str = "manual"
) -> None:
    connection.execute(
        "INSERT INTO blocked_players (player_uuid, reason, blocked_at, expires_at, source) VALUES (?, ?, ?, ?, ?)"
        " ON CONFLICT (player_uuid) DO UPDATE SET reason = excluded.reason, blocked_at = excluded.blocked_at,"
        " expires_at = excluded.expires_at, source = excluded.source",
        (player_uuid, reason, now, expires_at, source),
    )


def unblock_player(connection: sqlite3.Connection, player_uuid: str) -> bool:
    return connection.execute("DELETE FROM blocked_players WHERE player_uuid = ?", (player_uuid,)).rowcount > 0


def blocked_players(connection: sqlite3.Connection, now: float | None = None) -> list[Ban]:
    """Bans in force, oldest first. Expired bans are removed."""
    now = time.time() if now is None else now
    connection.execute("DELETE FROM blocked_players WHERE expires_at IS NOT NULL AND expires_at <= ?", (now,))
    rows = connection.execute("SELECT player_uuid, reason, blocked_at, expires_at, source FROM blocked_players ORDER BY blocked_at")
    return [Ban(*row) for row in rows]


# ---------------------------------------------------------------- featured cosmetics


def set_featured(
    connection: sqlite3.Connection, cosmetic_id: str, featured: bool, now_iso: str, author: str | None = None, description: str | None = None
) -> bool:
    if featured:
        return connection.execute(
            "UPDATE cosmetics SET featured = 1, featured_at = COALESCE(featured_at, ?),"
            " author = COALESCE(?, author), description = COALESCE(?, description) WHERE id = ?",
            (now_iso, author, description, cosmetic_id),
        ).rowcount > 0
    return connection.execute("UPDATE cosmetics SET featured = 0, featured_at = NULL WHERE id = ?", (cosmetic_id,)).rowcount > 0


def list_featured(connection: sqlite3.Connection, limit: int, offset: int) -> list[Cosmetic]:
    rows = connection.execute(
        "SELECT * FROM cosmetics WHERE featured = 1 AND validation_status = ? ORDER BY featured_at DESC, id LIMIT ? OFFSET ?",
        (STATUS_APPROVED, limit, offset),
    ).fetchall()
    return [Cosmetic.from_row(row) for row in rows]


def count_featured(connection: sqlite3.Connection) -> int:
    return connection.execute("SELECT COUNT(*) FROM cosmetics WHERE featured = 1 AND validation_status = ?", (STATUS_APPROVED,)).fetchone()[0]


def featured_hashes(connection: sqlite3.Connection, hashes: list[str]) -> set[str]:
    """The subset of ``hashes`` that belong to featured cosmetics."""
    if not hashes:
        return set()
    marks = ",".join("?" for _ in hashes)
    rows = connection.execute(f"SELECT sha256 FROM cosmetics WHERE featured = 1 AND sha256 IN ({marks})", hashes)
    return {row[0] for row in rows}


def names_by_hash(connection: sqlite3.Connection, hashes: list[str]) -> dict[str, Cosmetic]:
    if not hashes:
        return {}
    marks = ",".join("?" for _ in hashes)
    rows = connection.execute(f"SELECT * FROM cosmetics WHERE sha256 IN ({marks})", hashes).fetchall()
    return {row["sha256"]: Cosmetic.from_row(row) for row in rows}


# ---------------------------------------------------------------- reports


@dataclass(frozen=True)
class Report:
    player_uuid: str
    player_name: str
    reporter_uuid: str
    reason: str
    note: str
    cosmetics: str
    created_at: float


def prune_reports(connection: sqlite3.Connection, older_than: float) -> int:
    removed = connection.execute("DELETE FROM reports WHERE created_at < ?", (older_than,)).rowcount
    connection.execute("DELETE FROM report_actions WHERE at < ?", (older_than,))
    return removed


def add_report(connection: sqlite3.Connection, report: Report) -> bool:
    """Stores a report. Returns False if this reporter already reported this player (the report is refreshed)."""
    existing = connection.execute(
        "SELECT 1 FROM reports WHERE player_uuid = ? AND reporter_uuid = ?", (report.player_uuid, report.reporter_uuid)
    ).fetchone()
    connection.execute(
        """
        INSERT INTO reports (player_uuid, player_name, reporter_uuid, reason, note, cosmetics, created_at)
        VALUES (:player_uuid, :player_name, :reporter_uuid, :reason, :note, :cosmetics, :created_at)
        ON CONFLICT (player_uuid, reporter_uuid) DO UPDATE SET
            player_name = excluded.player_name, reason = excluded.reason, note = excluded.note, cosmetics = excluded.cosmetics
        """,
        report.__dict__,
    )
    return existing is None


def reports_for(connection: sqlite3.Connection, player_uuid: str) -> list[Report]:
    rows = connection.execute(
        "SELECT player_uuid, player_name, reporter_uuid, reason, note, cosmetics, created_at FROM reports"
        " WHERE player_uuid = ? ORDER BY created_at",
        (player_uuid,),
    )
    return [Report(*row) for row in rows]


def reports_by_since(connection: sqlite3.Connection, reporter_uuid: str, since: float) -> int:
    return connection.execute(
        "SELECT COUNT(*) FROM reports WHERE reporter_uuid = ? AND created_at >= ?", (reporter_uuid, since)
    ).fetchone()[0]


def reported_players(connection: sqlite3.Connection) -> list[tuple[str, str, int, float]]:
    """(uuid, latest name, distinct reporters, latest report time), most reported first."""
    rows = connection.execute(
        "SELECT player_uuid, MAX(player_name), COUNT(DISTINCT reporter_uuid), MAX(created_at) FROM reports"
        " GROUP BY player_uuid ORDER BY 3 DESC, 4 DESC"
    )
    return [tuple(row) for row in rows]


def clear_reports(connection: sqlite3.Connection, player_uuid: str) -> int:
    connection.execute("DELETE FROM report_actions WHERE player_uuid = ?", (player_uuid,))
    return connection.execute("DELETE FROM reports WHERE player_uuid = ?", (player_uuid,)).rowcount


def last_action(connection: sqlite3.Connection, player_uuid: str, kind: str) -> float | None:
    row = connection.execute("SELECT at FROM report_actions WHERE player_uuid = ? AND kind = ?", (player_uuid, kind)).fetchone()
    return row[0] if row else None


def record_action(connection: sqlite3.Connection, player_uuid: str, kind: str, at: float) -> None:
    connection.execute(
        "INSERT INTO report_actions (player_uuid, kind, at) VALUES (?, ?, ?) ON CONFLICT (player_uuid, kind) DO UPDATE SET at = excluded.at",
        (player_uuid, kind, at),
    )


def get_presence(connection: sqlite3.Connection, player_uuid: str) -> Presence | None:
    row = connection.execute("SELECT * FROM presence WHERE player_uuid = ?", (player_uuid,)).fetchone()
    return Presence(**{key: row[key] for key in row.keys()}) if row else None


def stored_filenames(connection: sqlite3.Connection) -> set[str]:
    return {row[0] for row in connection.execute("SELECT stored_filename FROM cosmetics")}

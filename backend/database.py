"""SQLite connection handling. Each request gets its own short-lived connection."""

from __future__ import annotations

import sqlite3
from collections.abc import Iterator
from pathlib import Path

from fastapi import Request

import models


def connect(database_path: Path) -> sqlite3.Connection:
    # FastAPI may open the connection in a worker thread and use it in the request's event loop thread.
    # Each connection belongs to exactly one request and is never used concurrently, so this is safe.
    connection = sqlite3.connect(database_path, timeout=10, check_same_thread=False)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    return connection


def init_database(database_path: Path) -> None:
    """Creates the database file and tables if needed. Existing data is never touched."""
    database_path.parent.mkdir(parents=True, exist_ok=True)
    connection = connect(database_path)
    try:
        # Write-ahead logging lets readers continue while an upload is being committed.
        connection.execute("PRAGMA journal_mode = WAL")
        with connection:
            models.create_schema(connection)
    finally:
        connection.close()


def get_connection(request: Request) -> Iterator[sqlite3.Connection]:
    """FastAPI dependency: a connection for the duration of one request."""
    connection = connect(request.app.state.settings.database_path)
    try:
        yield connection
    finally:
        connection.close()

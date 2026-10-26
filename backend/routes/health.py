"""Server status endpoints."""

from __future__ import annotations

import sqlite3

from fastapi import APIRouter, Depends, Request

import config
from database import get_connection
from schemas import HealthResponse, RootResponse

router = APIRouter(tags=["status"])


@router.get("/", response_model=RootResponse, summary="Project name and server status")
def root(request: Request) -> RootResponse:
    settings = request.app.state.settings
    return RootResponse(
        name=config.APP_NAME,
        version=config.APP_VERSION,
        status="ok",
        environment=settings.mode,
        docs="/docs",
    )


@router.get(f"{config.API_PREFIX}/health", response_model=HealthResponse, summary="Health check")
def health(request: Request, connection: sqlite3.Connection = Depends(get_connection)) -> HealthResponse:
    try:
        connection.execute("SELECT 1 FROM cosmetics LIMIT 1").fetchall()
        database = "ok"
    except sqlite3.Error:
        database = "error"
    storage = "ok" if request.app.state.storage.is_writable() else "error"
    status = "ok" if database == "ok" and storage == "ok" else "degraded"
    settings = request.app.state.settings
    return HealthResponse(status=status, database=database, storage=storage, mode=settings.mode, auth="mojang" if settings.requires_auth else "none")

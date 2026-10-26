"""Featured cosmetics: checked by hand, safe to use, and listed in the in-game catalog.

Featuring is done on the server machine with ``manage.py feature`` (or ``manage.py import --featured``). Banned
players may still wear featured cosmetics.
"""

from __future__ import annotations

import sqlite3

from fastapi import APIRouter, Depends, Query

import config
import models
from database import get_connection
from schemas import CosmeticResponse, FeaturedListResponse

router = APIRouter(prefix=f"{config.API_PREFIX}/featured", tags=["featured"])


@router.get("", response_model=FeaturedListResponse, summary="The featured catalog, newest first")
def list_featured(
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    connection: sqlite3.Connection = Depends(get_connection),
) -> FeaturedListResponse:
    items = [CosmeticResponse.from_model(c, config.API_PREFIX) for c in models.list_featured(connection, limit, offset)]
    return FeaturedListResponse(items=items, total=models.count_featured(connection), limit=limit, offset=offset)

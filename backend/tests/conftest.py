"""Every test gets its own temporary database and storage directory; real data is never touched."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))

import config  # noqa: E402
from main import create_app  # noqa: E402

SAMPLE = BACKEND / "samples" / "sample_hat.bbmodel"


def make_settings(tmp_path: Path, **overrides) -> config.Settings:
    values = dict(
        database_path=tmp_path / "data" / "test.sqlite3",
        storage_dir=tmp_path / "storage",
        max_upload_bytes=1024 * 1024,
        mode="development",
        # Starlette's test client reports its address as "testclient".
        dev_hosts=frozenset({"127.0.0.1", "::1", "localhost", "testclient"}),
    )
    values.update(overrides)
    return config.Settings(**values)


@pytest.fixture
def settings(tmp_path: Path) -> config.Settings:
    return make_settings(tmp_path)


@pytest.fixture
def client(settings: config.Settings):
    with TestClient(create_app(settings)) as test_client:
        yield test_client


@pytest.fixture
def sample_bytes() -> bytes:
    return SAMPLE.read_bytes()

"""The moderation command-line tool."""

from __future__ import annotations

from fastapi.testclient import TestClient

import manage
from conftest import make_settings
from main import create_app
from test_api import upload


def test_manage_commands(tmp_path, sample_bytes, monkeypatch, capsys):
    settings = make_settings(tmp_path)
    monkeypatch.setenv("ORBIT_DATABASE_PATH", str(settings.database_path))
    monkeypatch.setenv("ORBIT_STORAGE_DIR", str(settings.storage_dir))
    with TestClient(create_app(settings)) as client:
        cosmetic_id = upload(client, sample_bytes).json()["id"]

    assert manage.main(["stats"]) == 0
    assert "cosmetics:         1" in capsys.readouterr().out
    assert manage.main(["list"]) == 0
    assert cosmetic_id in capsys.readouterr().out
    assert manage.main(["block", "069a79f4-44e9-4726-a5be-fca90e38aaf5", "--reason", "test"]) == 0
    assert manage.main(["blocked"]) == 0
    assert "069a79f4-44e9-4726-a5be-fca90e38aaf5" in capsys.readouterr().out
    assert manage.main(["unblock", "069a79f4-44e9-4726-a5be-fca90e38aaf5"]) == 0
    assert manage.main(["backup", str(tmp_path / "backups")]) == 0
    backup = next((tmp_path / "backups").iterdir())
    assert (backup / "orbit.sqlite3").exists()
    assert (backup / "cosmetics" / f"{cosmetic_id}.bbmodel").read_bytes() == sample_bytes
    assert manage.main(["delete", cosmetic_id]) == 0
    assert not (settings.storage_dir / f"{cosmetic_id}.bbmodel").exists()
    assert manage.main(["show", cosmetic_id]) == 1

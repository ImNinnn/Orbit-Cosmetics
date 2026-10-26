"""Presence and SHA-256 lookups, used by Orbit clients on servers without the Orbit server mod or plugin."""

from __future__ import annotations

import base64
import hashlib
import sqlite3

from fastapi.testclient import TestClient

from conftest import make_settings
from main import create_app
from test_api import assert_error, upload

ALICE = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
BOB = "853c80ef-3c37-49fd-aa49-938b674adae6"
EQUIP = base64.b64encode(b"\x02\x00").decode()


def report(client, player=ALICE, name="Alice", equip=EQUIP, protocol=2):
    return client.put(f"/v1/presence/{player}", json={"player_name": name, "protocol": protocol, "equip": equip})


def test_report_and_query(client):
    response = report(client)
    assert response.status_code == 200, response.text
    assert response.json() == {"player_uuid": ALICE, "ttl_seconds": 90}

    result = client.post("/v1/presence/query", json={"players": [ALICE.upper(), BOB]}).json()
    assert result["ttl_seconds"] == 90
    assert len(result["players"]) == 1
    entry = result["players"][0]
    assert entry["player_uuid"] == ALICE
    assert entry["player_name"] == "Alice"
    assert entry["protocol"] == 2
    assert entry["equip"] == EQUIP

    # Reporting again replaces the previous entry.
    other = base64.b64encode(b"\x02\x01").decode()
    report(client, equip=other)
    assert client.post("/v1/presence/query", json={"players": [ALICE]}).json()["players"][0]["equip"] == other


def test_presence_expires(client, monkeypatch):
    import routes.presence as presence

    clock = [1_000_000.0]
    monkeypatch.setattr(presence.time, "time", lambda: clock[0])
    report(client)
    clock[0] += 89
    assert len(client.post("/v1/presence/query", json={"players": [ALICE]}).json()["players"]) == 1
    clock[0] += 2
    assert client.post("/v1/presence/query", json={"players": [ALICE]}).json()["players"] == []


def test_delete_presence(client):
    report(client)
    assert client.delete(f"/v1/presence/{ALICE}").status_code == 200
    assert client.post("/v1/presence/query", json={"players": [ALICE]}).json()["players"] == []
    assert_error(client.delete(f"/v1/presence/{ALICE}"), 404, "not_found")


def test_presence_input_is_validated(client):
    assert_error(report(client, player="not-a-uuid"), 400, "invalid_uuid")
    assert_error(report(client, name="bad name!"), 400, "invalid_name")
    assert_error(report(client, equip="%%% not base64 %%%"), 400, "invalid_equip")
    assert_error(report(client, equip=base64.b64encode(b"x" * 32_001).decode()), 400, "invalid_equip")
    assert_error(report(client, equip=""), 400, "invalid_equip")
    assert_error(client.post("/v1/presence/query", json={"players": ["nope"]}), 400, "invalid_uuid")
    assert_error(client.post("/v1/presence/query", json={"players": [ALICE] * 201}), 422, "invalid_request")


def test_presence_writes_are_development_only(tmp_path):
    with TestClient(create_app(make_settings(tmp_path, mode="read-only"))) as client:
        assert_error(report(client), 403, "read_only")
        assert_error(client.delete(f"/v1/presence/{ALICE}"), 403, "read_only")
        assert client.post("/v1/presence/query", json={"players": [ALICE]}).status_code == 200


def test_lookup_by_sha256(client, sample_bytes):
    uploaded = upload(client, sample_bytes).json()
    sha = hashlib.sha256(sample_bytes).hexdigest()
    assert client.get(f"/v1/cosmetics/by-sha256/{sha}").json()["id"] == uploaded["id"]
    assert client.get(f"/v1/cosmetics/by-sha256/{sha.upper()}").status_code == 200
    download = client.get(f"/v1/cosmetics/by-sha256/{sha}/download")
    assert download.status_code == 200
    assert download.content == sample_bytes
    assert_error(client.get(f"/v1/cosmetics/by-sha256/{'0' * 64}"), 404, "not_found")
    assert_error(client.get("/v1/cosmetics/by-sha256/zz/download"), 404, "not_found")


def test_database_from_first_prototype_is_upgraded(tmp_path, sample_bytes):
    settings = make_settings(tmp_path)
    with TestClient(create_app(settings)) as client:
        cosmetic_id = upload(client, sample_bytes).json()["id"]
    # Turn it back into a version 1 database (no presence table).
    connection = sqlite3.connect(settings.database_path)
    connection.execute("DROP TABLE presence")
    connection.execute("PRAGMA user_version = 1")
    connection.commit()
    connection.close()
    with TestClient(create_app(settings)) as client:
        assert client.get(f"/v1/cosmetics/{cosmetic_id}").status_code == 200
        assert report(client).status_code == 200
    connection = sqlite3.connect(settings.database_path)
    assert connection.execute("PRAGMA user_version").fetchone()[0] == 4
    connection.close()

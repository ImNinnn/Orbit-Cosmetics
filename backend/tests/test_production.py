"""Production mode: Mojang-verified sessions, ownership, quotas, rate limits and proxy handling."""

from __future__ import annotations

import base64
import sqlite3

import pytest
from fastapi.testclient import TestClient

import auth
from conftest import make_settings
from main import create_app
from test_api import assert_error, upload

ALICE_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
BOB_UUID = "853c80ef-3c37-49fd-aa49-938b674adae6"
EQUIP = base64.b64encode(b"\x02\x00").decode()


@pytest.fixture
def mojang(monkeypatch):
    """Simulates Mojang's hasJoined: players listed here have "joined" any server ID."""
    joined = {"alice": (ALICE_UUID, "Alice"), "bob": (BOB_UUID, "Bob")}
    calls = []

    def has_joined(url, username, server_id, timeout=5.0):
        calls.append((username, server_id))
        if joined.get("unavailable"):
            raise auth.MojangUnavailable("down")
        return joined.get(username.lower())

    monkeypatch.setattr(auth, "mojang_has_joined", has_joined)
    return joined, calls


@pytest.fixture
def prod(tmp_path, mojang):
    with TestClient(create_app(make_settings(tmp_path, mode="production", uploads_per_player_per_day=2))) as client:
        yield client


def sign_in(client, username="Alice") -> str:
    server_id = client.post("/v1/auth/challenge").json()["server_id"]
    response = client.post("/v1/auth/session", json={"username": username, "server_id": server_id})
    assert response.status_code == 200, response.text
    return response.json()["token"]


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def test_health_reports_production_auth(prod):
    body = prod.get("/v1/health").json()
    assert body["mode"] == "production"
    assert body["auth"] == "mojang"
    assert prod.get("/").json()["environment"] == "production"


def test_sign_in_flow(prod, mojang):
    _, calls = mojang
    challenge = prod.post("/v1/auth/challenge").json()
    assert len(challenge["server_id"]) == 40
    response = prod.post("/v1/auth/session", json={"username": "alice", "server_id": challenge["server_id"]})
    assert response.status_code == 200
    body = response.json()
    assert body["player_uuid"] == ALICE_UUID
    assert body["player_name"] == "Alice"
    assert calls == [("alice", challenge["server_id"])]
    me = prod.get("/v1/auth/me", headers=bearer(body["token"])).json()
    assert me["player_name"] == "Alice" and me["token"] is None

    # Challenges are single-use.
    assert_error(prod.post("/v1/auth/session", json={"username": "alice", "server_id": challenge["server_id"]}), 401, "invalid_challenge")


def test_sign_in_refused_when_mojang_does_not_confirm(prod):
    server_id = prod.post("/v1/auth/challenge").json()["server_id"]
    assert_error(prod.post("/v1/auth/session", json={"username": "Mallory", "server_id": server_id}), 401, "not_authenticated")
    assert_error(prod.post("/v1/auth/session", json={"username": "alice", "server_id": "f" * 40}), 401, "invalid_challenge")
    assert_error(prod.post("/v1/auth/session", json={"username": "bad name", "server_id": server_id}), 400, "invalid_request")


def test_mojang_outage(prod, mojang):
    joined, _ = mojang
    joined["unavailable"] = True
    server_id = prod.post("/v1/auth/challenge").json()["server_id"]
    assert_error(prod.post("/v1/auth/session", json={"username": "alice", "server_id": server_id}), 502, "mojang_unavailable")


def test_expired_challenge(prod, monkeypatch):
    import routes.auth as auth_routes

    clock = [1_000_000.0]
    monkeypatch.setattr(auth_routes.time, "time", lambda: clock[0])
    server_id = prod.post("/v1/auth/challenge").json()["server_id"]
    clock[0] += 121
    assert_error(prod.post("/v1/auth/session", json={"username": "alice", "server_id": server_id}), 401, "invalid_challenge")


def test_writes_require_a_session(prod, sample_bytes):
    assert_error(upload(prod, sample_bytes), 401, "not_authenticated")
    assert_error(prod.put(f"/v1/presence/{ALICE_UUID}", json={"player_name": "Alice", "protocol": 2, "equip": EQUIP}), 401, "not_authenticated")
    assert_error(prod.post("/v1/cosmetics/upload", files={"file": ("a.bbmodel", sample_bytes)}, headers=bearer("forged")), 401, "not_authenticated")


def test_upload_records_the_owner(prod, sample_bytes):
    token = sign_in(prod)
    response = prod.post("/v1/cosmetics/upload", files={"file": ("hat.bbmodel", sample_bytes)}, headers=bearer(token))
    assert response.status_code == 201, response.text
    assert response.json()["owner_uuid"] == ALICE_UUID


def test_daily_upload_quota(prod, sample_bytes):
    import json

    token = sign_in(prod)
    for i in range(3):
        model = json.loads(sample_bytes)
        model["name"] = f"hat {i}"
        response = prod.post("/v1/cosmetics/upload", files={"file": ("hat.bbmodel", json.dumps(model).encode())}, headers=bearer(token))
        if i < 2:
            assert response.status_code == 201
        else:
            assert_error(response, 429, "quota_exceeded")


def test_storage_limit(tmp_path, mojang, sample_bytes):
    with TestClient(create_app(make_settings(tmp_path, mode="production", max_storage_bytes=len(sample_bytes) - 1))) as client:
        token = sign_in(client)
        response = client.post("/v1/cosmetics/upload", files={"file": ("hat.bbmodel", sample_bytes)}, headers=bearer(token))
        assert_error(response, 507, "storage_full")


def test_players_can_only_publish_for_themselves(prod):
    token = sign_in(prod)
    body = {"player_name": "SomeoneElse", "protocol": 2, "equip": EQUIP}
    assert prod.put(f"/v1/presence/{ALICE_UUID}", json=body, headers=bearer(token)).status_code == 200
    assert_error(prod.put(f"/v1/presence/{BOB_UUID}", json=body, headers=bearer(token)), 403, "not_your_player")
    assert_error(prod.delete(f"/v1/presence/{BOB_UUID}", headers=bearer(token)), 403, "not_your_player")
    # The stored name is the verified one, not what the client claimed.
    entry = prod.post("/v1/presence/query", json={"players": [ALICE_UUID]}).json()["players"][0]
    assert entry["player_name"] == "Alice"


def test_query_by_name_for_offline_mode_servers(prod):
    token = sign_in(prod)
    prod.put(f"/v1/presence/{ALICE_UUID}", json={"player_name": "Alice", "protocol": 2, "equip": EQUIP}, headers=bearer(token))
    offline_uuid = "00000000-0000-3000-8000-000000000001"
    result = prod.post("/v1/presence/query", json={"players": [offline_uuid], "names": ["alice", "not a name!"]}).json()
    assert [entry["player_uuid"] for entry in result["players"]] == [ALICE_UUID]


def test_blocked_players_cannot_write(prod, sample_bytes, settings):
    token = sign_in(prod)
    connection = sqlite3.connect(prod.app.state.settings.database_path)
    connection.execute("INSERT INTO blocked_players (player_uuid, reason, blocked_at) VALUES (?, 'test', 0)", (ALICE_UUID,))
    connection.commit()
    connection.close()
    response = prod.post("/v1/cosmetics/upload", files={"file": ("hat.bbmodel", sample_bytes)}, headers=bearer(token))
    assert_error(response, 403, "blocked")


def test_no_deletion_over_http_in_production(prod, sample_bytes):
    token = sign_in(prod)
    cosmetic_id = prod.post("/v1/cosmetics/upload", files={"file": ("hat.bbmodel", sample_bytes)}, headers=bearer(token)).json()["id"]
    assert_error(prod.delete(f"/v1/cosmetics/{cosmetic_id}", headers=bearer(token)), 403, "development_only")


def test_sign_out(prod):
    token = sign_in(prod)
    assert prod.delete("/v1/auth/session", headers=bearer(token)).json() == {"signed_out": True}
    assert_error(prod.get("/v1/auth/me", headers=bearer(token)), 401, "not_authenticated")


def test_auth_rate_limit(tmp_path, mojang):
    with TestClient(create_app(make_settings(tmp_path, mode="production", auth_requests_per_minute=3))) as client:
        for _ in range(3):
            assert client.post("/v1/auth/challenge").status_code == 200
        response = client.post("/v1/auth/challenge")
        assert_error(response, 429, "too_many_requests")
        assert response.headers["retry-after"] == "60"
        assert client.get("/v1/health").status_code == 200, "other endpoints have their own limit"


def test_rate_limit_is_per_visitor_behind_the_tunnel(tmp_path):
    settings = make_settings(tmp_path, mode="read-only", requests_per_minute=2)
    with TestClient(create_app(settings), client=("127.0.0.1", 40000)) as client:
        visitor_a = {"CF-Connecting-IP": "203.0.113.5"}
        visitor_b = {"CF-Connecting-IP": "198.51.100.7"}
        assert client.get("/v1/health", headers=visitor_a).status_code == 200
        assert client.get("/v1/health", headers=visitor_a).status_code == 200
        assert_error(client.get("/v1/health", headers=visitor_a), 429, "too_many_requests")
        assert client.get("/v1/health", headers=visitor_b).status_code == 200


def test_proxy_headers_from_outside_are_ignored(tmp_path):
    settings = make_settings(tmp_path, mode="read-only", requests_per_minute=2)
    with TestClient(create_app(settings), client=("203.0.113.9", 40000)) as client:
        # A direct visitor cannot dodge the limit by inventing CF-Connecting-IP values.
        assert client.get("/v1/health", headers={"CF-Connecting-IP": "1.1.1.1"}).status_code == 200
        assert client.get("/v1/health", headers={"CF-Connecting-IP": "2.2.2.2"}).status_code == 200
        assert_error(client.get("/v1/health", headers={"CF-Connecting-IP": "3.3.3.3"}), 429, "too_many_requests")


def test_development_mode_refuses_requests_relayed_by_a_tunnel(tmp_path, sample_bytes):
    """If a tunnel were accidentally pointed at a development server, internet traffic must not be able to write."""
    with TestClient(create_app(make_settings(tmp_path)), client=("127.0.0.1", 40000)) as client:
        relayed = client.post("/v1/cosmetics/upload", files={"file": ("hat.bbmodel", sample_bytes)}, headers={"CF-Connecting-IP": "203.0.113.5"})
        assert_error(relayed, 403, "development_only")
        assert client.post("/v1/cosmetics/upload", files={"file": ("hat.bbmodel", sample_bytes)}).status_code == 201


def test_cors_allows_the_website_only(tmp_path):
    settings = make_settings(tmp_path, mode="read-only", cors_origins=("https://orbitcosmetics.qd.je",))
    with TestClient(create_app(settings)) as client:
        allowed = client.get("/v1/cosmetics", headers={"Origin": "https://orbitcosmetics.qd.je"})
        assert allowed.headers["access-control-allow-origin"] == "https://orbitcosmetics.qd.je"
        other = client.get("/v1/cosmetics", headers={"Origin": "https://evil.example"})
        assert "access-control-allow-origin" not in other.headers


def test_version_2_database_gains_owner_column(tmp_path, sample_bytes):
    settings = make_settings(tmp_path)
    settings.database_path.parent.mkdir(parents=True)
    connection = sqlite3.connect(settings.database_path)
    connection.executescript(
        """
        CREATE TABLE cosmetics (id TEXT PRIMARY KEY, display_name TEXT NOT NULL, original_filename TEXT NOT NULL,
            stored_filename TEXT NOT NULL UNIQUE, size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL UNIQUE,
            uploaded_at TEXT NOT NULL, validation_status TEXT NOT NULL, format_version TEXT, model_format TEXT,
            element_count INTEGER NOT NULL DEFAULT 0, texture_count INTEGER NOT NULL DEFAULT 0, animation_count INTEGER NOT NULL DEFAULT 0);
        PRAGMA user_version = 2;
        """
    )
    connection.close()
    with TestClient(create_app(settings)) as client:
        assert upload(client, sample_bytes).status_code == 201
    connection = sqlite3.connect(settings.database_path)
    assert connection.execute("PRAGMA user_version").fetchone()[0] == 4
    assert "owner_uuid" in {row[1] for row in connection.execute("PRAGMA table_info(cosmetics)")}
    connection.close()


def test_real_mojang_response_parsing(monkeypatch):
    """The parser for Mojang's hasJoined response, without network access."""
    import io
    import urllib.request

    class FakeResponse(io.BytesIO):
        status = 200

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

    body = b'{"id": "069a79f444e94726a5befca90e38aaf5", "name": "Notch", "properties": []}'
    monkeypatch.setattr(urllib.request, "urlopen", lambda request, timeout: FakeResponse(body))
    assert auth.mojang_has_joined("https://example.invalid", "Notch", "abc") == ("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch")
    monkeypatch.setattr(urllib.request, "urlopen", lambda request, timeout: FakeResponse(b""))
    assert auth.mojang_has_joined("https://example.invalid", "Notch", "abc") is None
    monkeypatch.setattr(urllib.request, "urlopen", lambda request, timeout: FakeResponse(b'{"id": "../../etc", "name": "x"}'))
    with pytest.raises(auth.MojangUnavailable):
        auth.mojang_has_joined("https://example.invalid", "Notch", "abc")

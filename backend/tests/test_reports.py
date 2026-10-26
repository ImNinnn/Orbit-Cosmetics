"""Reports, automatic Discord alerts and bans, the featured catalog, and what banned players may still do."""

from __future__ import annotations

import base64
import hashlib
import sqlite3
import time

import pytest
from fastapi.testclient import TestClient

import auth
import manage
import models
from conftest import SAMPLE, make_settings
from main import create_app
from services import discord
from services.equip import InvalidEquip, worn_cosmetics
from test_api import assert_error, upload
from test_production import bearer, sign_in

TOM = "11111111-1111-4111-8111-111111111111"
REPORTERS = [f"2222222{i}-2222-4222-8222-222222222222" for i in range(1, 8)]


def equip(*cosmetics: tuple[str, str]) -> str:
    """An Orbit Equip message wearing the given (sha256, name) cosmetics, without animations or physics."""
    data = bytearray([0x02, len(cosmetics)])
    for sha, name in cosmetics:
        encoded = name.encode()
        data += bytes.fromhex(sha) + len(encoded).to_bytes(2, "big") + encoded + b"\x00\x00"
    return base64.b64encode(bytes(data)).decode()


class FakeNotifier:
    def __init__(self) -> None:
        self.alerts: list[discord.ReportAlert] = []

    def send(self, alert: discord.ReportAlert) -> None:
        self.alerts.append(alert)


@pytest.fixture
def players(monkeypatch):
    joined = {"tom": (TOM, "Tom")}
    for index, uuid in enumerate(REPORTERS, start=1):
        joined[f"r{index}"] = (uuid, f"R{index}")
    monkeypatch.setattr(auth, "mojang_has_joined", lambda url, username, server_id, timeout=5.0: joined.get(username.lower()))
    return joined


@pytest.fixture
def cloud(tmp_path, players):
    settings = make_settings(tmp_path, mode="production", auth_requests_per_minute=1000)
    with TestClient(create_app(settings)) as client:
        client.app.state.notifier = FakeNotifier()
        yield client, settings


def report(client, token, target=TOM, name="Tom", reason="inappropriate", **extra):
    return client.post("/v1/reports", json={"player_uuid": target, "player_name": name, "reason": reason, **extra}, headers=bearer(token))


def test_equip_decoder_reads_assets_and_names():
    sha = "ab" * 32
    cosmetics = worn_cosmetics(base64.b64decode(equip((sha, "Halo"), ("cd" * 32, "Wings"))))
    assert [(c.sha256, c.name) for c in cosmetics] == [(sha, "Halo"), ("cd" * 32, "Wings")]
    assert worn_cosmetics(b"\x02\x00") == []
    for broken in (b"", b"\x01\x00", b"\x02\x01" + b"\x00" * 10, b"\x02\x00extra", b"\x02\x11"):
        with pytest.raises(InvalidEquip):
            worn_cosmetics(broken)


def test_three_reporters_alert_moderators_and_six_ban_for_a_week(cloud, sample_bytes):
    client, settings = cloud
    notifier: FakeNotifier = client.app.state.notifier
    tom = sign_in(client, "Tom")
    assert client.post("/v1/cosmetics/upload", files={"file": ("hat.bbmodel", sample_bytes)}, headers=bearer(tom)).status_code == 201
    sha = hashlib.sha256(sample_bytes).hexdigest()
    assert client.put(f"/v1/presence/{TOM}", json={"player_name": "Tom", "protocol": 2, "equip": equip((sha, "Sample hat"))}, headers=bearer(tom)).status_code == 200

    tokens = [sign_in(client, f"R{i}") for i in range(1, 7)]
    for token in tokens[:2]:
        assert report(client, token, note="rude <@everyone> words").status_code == 201
    assert notifier.alerts == []
    # Reporting again only updates the report; it is still one reporter.
    again = report(client, tokens[0], reason="offensive")
    assert again.status_code == 201 and again.json()["new"] is False
    assert notifier.alerts == []

    assert report(client, tokens[2], reason="lag").json()["new"] is True
    assert len(notifier.alerts) == 1
    alert = notifier.alerts[0]
    assert (alert.player_uuid, alert.player_name, alert.reporters, alert.banned_days) == (TOM, "Tom", 3, None)
    assert alert.cosmetics[0].sha256 == sha and alert.cosmetics[0].name == "Sample hat" and alert.cosmetics[0].cosmetic_id
    assert alert.reasons == {"offensive": 1, "inappropriate": 1, "lag": 1}

    for token in tokens[3:5]:
        report(client, token)
    assert len(notifier.alerts) == 1, "one alert per week, not one per report"

    report(client, tokens[5])
    assert len(notifier.alerts) == 2
    ban_alert = notifier.alerts[1]
    assert ban_alert.banned_days == 7 and ban_alert.reporters == 6

    # Banned: what Tom wore disappears, uploads and reports are refused, sign-in tells the client.
    assert client.post("/v1/presence/query", json={"players": [TOM]}).json()["players"] == []
    me = client.get("/v1/auth/me", headers=bearer(tom)).json()
    assert me["banned"] is True and abs(me["banned_until"] - (time.time() + 7 * 86400)) < 60
    assert_error(client.put(f"/v1/presence/{TOM}", json={"player_name": "Tom", "protocol": 2, "equip": equip((sha, "Sample hat"))}, headers=bearer(tom)), 403, "blocked")
    other = sample_bytes.replace(b"Sample", b"Simple")
    assert_error(client.post("/v1/cosmetics/upload", files={"file": ("other.bbmodel", other)}, headers=bearer(tom)), 403, "blocked")
    assert_error(report(client, tom, target=REPORTERS[0], name="R1"), 403, "blocked")
    # Nothing worn is always allowed.
    assert client.put(f"/v1/presence/{TOM}", json={"player_name": "Tom", "protocol": 2, "equip": equip()}, headers=bearer(tom)).status_code == 200

    # Featured cosmetics may still be worn.
    with sqlite3.connect(settings.database_path) as connection:
        connection.execute("UPDATE cosmetics SET featured = 1 WHERE sha256 = ?", (sha,))
    assert client.put(f"/v1/presence/{TOM}", json={"player_name": "Tom", "protocol": 2, "equip": equip((sha, "Sample hat"))}, headers=bearer(tom)).status_code == 200
    mixed = equip((sha, "Sample hat"), ("ee" * 32, "Not featured"))
    assert_error(client.put(f"/v1/presence/{TOM}", json={"player_name": "Tom", "protocol": 2, "equip": mixed}, headers=bearer(tom)), 403, "blocked")


def test_bans_end_and_old_reports_are_dropped(cloud):
    client, settings = cloud
    now = time.time()
    with sqlite3.connect(settings.database_path) as connection:
        models.block_player(connection, TOM, "test", now - 10 * 86400, now - 3 * 86400, source="reports")
        for index, reporter in enumerate(REPORTERS[:5]):
            connection.execute(
                "INSERT INTO reports (player_uuid, player_name, reporter_uuid, reason, created_at) VALUES (?, 'Tom', ?, 'other', ?)",
                (TOM, reporter, now - 8 * 86400 + index),
            )
    tom = sign_in(client, "Tom")
    assert client.get("/v1/auth/me", headers=bearer(tom)).json()["banned"] is False
    # A new report prunes the week-old ones: this is one reporter, not six, so no alert and no ban.
    assert report(client, sign_in(client, "R6")).status_code == 201
    assert client.app.state.notifier.alerts == []
    with sqlite3.connect(settings.database_path) as connection:
        assert connection.execute("SELECT COUNT(*) FROM reports").fetchone()[0] == 1


def test_report_validation(cloud):
    client, settings = cloud
    r1 = sign_in(client, "R1")
    assert_error(client.post("/v1/reports", json={"player_uuid": TOM, "player_name": "Tom", "reason": "other"}), 401, "not_authenticated")
    assert_error(report(client, r1, target=REPORTERS[0], name="R1"), 400, "cannot_report_self")
    assert_error(report(client, r1, reason="because"), 400, "invalid_reason")
    assert_error(report(client, r1, target="not-a-uuid"), 400, "invalid_uuid")
    assert_error(report(client, r1, name="Tom with spaces"), 400, "invalid_name")
    assert report(client, r1, cosmetics=["ab" * 32, "not a hash", "AB" * 32]).status_code == 201
    with sqlite3.connect(settings.database_path) as connection:
        assert connection.execute("SELECT cosmetics FROM reports").fetchone()[0] == f'["{"ab" * 32}"]'


def test_daily_report_limit(tmp_path, players):
    with TestClient(create_app(make_settings(tmp_path, mode="production", auth_requests_per_minute=1000, reports_per_player_per_day=2))) as client:
        client.app.state.notifier = FakeNotifier()
        r1 = sign_in(client, "R1")
        assert report(client, r1, target=REPORTERS[1], name="R2").status_code == 201
        assert report(client, r1, target=REPORTERS[2], name="R3").status_code == 201
        assert_error(report(client, r1), 429, "report_limit")


def test_development_reports_name_the_reporter(client):
    client.app.state.notifier = FakeNotifier()
    body = {"player_uuid": TOM, "player_name": "Tom", "reason": "other"}
    assert_error(client.post("/v1/reports", json=body), 400, "invalid_uuid")
    assert client.post("/v1/reports", json={**body, "reporter_uuid": REPORTERS[0]}).status_code == 201
    forwarded = client.post("/v1/reports", json={**body, "reporter_uuid": REPORTERS[1]}, headers={"X-Forwarded-For": "203.0.113.9"})
    assert_error(forwarded, 403, "development_only")


def test_development_bans_apply_to_the_reported_uuid(client, settings, sample_bytes):
    with sqlite3.connect(settings.database_path) as connection:
        models.block_player(connection, TOM, "test", time.time(), None)
    assert upload(client, sample_bytes).status_code == 201
    sha = hashlib.sha256(sample_bytes).hexdigest()
    put = lambda: client.put(f"/v1/presence/{TOM}", json={"player_name": "Tom", "protocol": 2, "equip": equip((sha, "Hat"))})
    assert_error(put(), 403, "blocked")
    with sqlite3.connect(settings.database_path) as connection:
        connection.execute("UPDATE cosmetics SET featured = 1")
    assert put().status_code == 200


def test_featured_catalog_and_manage_commands(tmp_path, monkeypatch, capsys):
    settings = make_settings(tmp_path)
    monkeypatch.setenv("ORBIT_DATABASE_PATH", str(settings.database_path))
    monkeypatch.setenv("ORBIT_STORAGE_DIR", str(settings.storage_dir))
    assert manage.main(["import", str(SAMPLE), "--featured", "--author", "Orbit", "--description", "A tiny hat"]) == 0
    assert manage.main(["import", str(SAMPLE)]) == 1, "duplicates are refused"
    capsys.readouterr()
    with TestClient(create_app(settings)) as client:
        catalog = client.get("/v1/featured").json()
        assert catalog["total"] == 1
        item = catalog["items"][0]
        assert item["featured"] is True and item["author"] == "Orbit" and item["description"] == "A tiny hat"
        assert client.get(item["download_url"]).content == SAMPLE.read_bytes()
        assert manage.main(["unfeature", item["id"]]) == 0
        assert client.get("/v1/featured").json()["total"] == 0
        assert manage.main(["feature", item["id"]]) == 0
        assert client.get("/v1/featured").json()["items"][0]["author"] == "Orbit"

    assert manage.main(["block", TOM, "--days", "7", "--reason", "spam"]) == 0
    assert manage.main(["blocked"]) == 0
    out = capsys.readouterr().out
    assert TOM in out and "spam" in out and "until 20" in out
    with sqlite3.connect(settings.database_path) as connection:
        connection.execute(
            "INSERT INTO reports (player_uuid, player_name, reporter_uuid, reason, note, created_at) VALUES (?, 'Tom', ?, 'lag', 'very laggy', ?)",
            (TOM, REPORTERS[0], time.time()),
        )
    assert manage.main(["reports"]) == 0
    assert "1 reporter(s)" in capsys.readouterr().out
    assert manage.main(["reports", TOM]) == 0
    assert "very laggy" in capsys.readouterr().out
    assert manage.main(["clear-reports", TOM]) == 0
    assert "Deleted 1" in capsys.readouterr().out
    assert manage.main(["test-webhook"]) == 1, "no webhook configured"


def test_import_refuses_external_textures(tmp_path, monkeypatch):
    settings = make_settings(tmp_path)
    monkeypatch.setenv("ORBIT_DATABASE_PATH", str(settings.database_path))
    monkeypatch.setenv("ORBIT_STORAGE_DIR", str(settings.storage_dir))
    model = tmp_path / "external.bbmodel"
    model.write_text('{"meta": {"format_version": "4.10"}, "elements": [], "outliner": [], "textures": [{"name": "skin.png", "path": "skin.png"}]}')
    assert manage.main(["import", str(model), "--featured"]) == 1


def test_discord_messages_are_safe():
    assert discord.valid_webhook_url("https://discord.com/api/webhooks/1/abc")
    assert not discord.valid_webhook_url("http://discord.com/api/webhooks/1/abc")
    assert not discord.valid_webhook_url("https://evil.example/api/webhooks/1/abc")
    assert not discord.valid_webhook_url("https://discord.com/channels/1")
    assert not discord.valid_webhook_url("http://127.0.0.1:9999/hook")
    assert discord.valid_webhook_url("http://127.0.0.1:9999/hook", allow_local=True)
    assert not discord.DiscordNotifier("https://evil.example/x").enabled

    alert = discord.ReportAlert(TOM, "Tom_1", 3, {"other": 3}, ["@everyone look https://x.y **now**"], [discord.ReportedCosmetic("ab" * 32, "`Halo`")])
    message = discord.build_message(alert)
    assert message["allowed_mentions"] == {"parse": []}
    text = str(message)
    assert "Tom\\\\_1" in text and TOM in text and "abababababababab" in text
    assert "**now**" not in text and "`Halo`" not in text and "https://" not in text

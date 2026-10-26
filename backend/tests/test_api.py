"""API tests. Run from the backend directory with:  python -m pytest"""

from __future__ import annotations

import hashlib
import json

from fastapi.testclient import TestClient

from conftest import make_settings
from main import create_app


def upload(client: TestClient, data: bytes, filename: str = "hat.bbmodel", **form):
    return client.post("/v1/cosmetics/upload", files={"file": (filename, data, "application/octet-stream")}, data=form)


def assert_error(response, status: int, code: str) -> None:
    assert response.status_code == status, response.text
    body = response.json()
    assert body["error"]["code"] == code, body
    assert body["error"]["message"]


# ---------------------------------------------------------------- status


def test_root(client):
    body = client.get("/").json()
    assert body["name"] == "Orbit Cosmetics Backend"
    assert body["status"] == "ok"
    assert body["docs"] == "/docs"


def test_health(client):
    response = client.get("/v1/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "database": "ok", "storage": "ok", "mode": "development", "auth": "none"}


def test_docs_are_available(client):
    assert client.get("/docs").status_code == 200
    assert "/v1/cosmetics/upload" in client.get("/openapi.json").json()["paths"]


# ---------------------------------------------------------------- catalogue


def test_empty_catalogue(client):
    response = client.get("/v1/cosmetics")
    assert response.status_code == 200
    assert response.json() == {"items": [], "total": 0, "limit": 50, "offset": 0}


def test_upload_valid_bbmodel(client, settings, sample_bytes):
    response = upload(client, sample_bytes, display_name="My hat")
    assert response.status_code == 201, response.text
    body = response.json()
    assert len(body["id"]) == 32
    assert body["display_name"] == "My hat"
    assert body["original_filename"] == "hat.bbmodel"
    assert body["size_bytes"] == len(sample_bytes)
    assert body["sha256"] == hashlib.sha256(sample_bytes).hexdigest()
    assert body["validation_status"] == "approved"
    assert body["element_count"] == 1
    assert body["download_url"] == f"/v1/cosmetics/{body['id']}/download"
    assert "stored_filename" not in body
    # Stored under a generated name, never the uploaded one.
    assert [p.name for p in settings.storage_dir.iterdir() if p.suffix == ".bbmodel"] == [f"{body['id']}.bbmodel"]

    catalogue = client.get("/v1/cosmetics").json()
    assert catalogue["total"] == 1
    assert catalogue["items"][0]["id"] == body["id"]


def test_display_name_defaults_to_model_name(client, sample_bytes):
    assert upload(client, sample_bytes).json()["display_name"] == "Sample hat"


def test_json_extension_is_accepted(client, sample_bytes):
    assert upload(client, sample_bytes, filename="hat.json").status_code == 201


def test_get_metadata(client, sample_bytes):
    cosmetic_id = upload(client, sample_bytes).json()["id"]
    response = client.get(f"/v1/cosmetics/{cosmetic_id}")
    assert response.status_code == 200
    assert response.json()["id"] == cosmetic_id


def test_download_returns_identical_bytes(client, sample_bytes):
    cosmetic_id = upload(client, sample_bytes).json()["id"]
    response = client.get(f"/v1/cosmetics/{cosmetic_id}/download")
    assert response.status_code == 200
    assert response.content == sample_bytes
    assert response.headers["content-type"].startswith("application/json")
    assert "hat.bbmodel" in response.headers["content-disposition"]
    assert response.headers["x-orbit-sha256"] == hashlib.sha256(sample_bytes).hexdigest()


def test_pagination(client, sample_bytes):
    for i in range(3):
        model = json.loads(sample_bytes)
        model["name"] = f"hat {i}"
        upload(client, json.dumps(model).encode())
    page = client.get("/v1/cosmetics", params={"limit": 2, "offset": 1}).json()
    assert page["total"] == 3
    assert len(page["items"]) == 2
    assert_error(client.get("/v1/cosmetics", params={"limit": 0}), 422, "invalid_request")


# ---------------------------------------------------------------- rejected uploads


def test_rejects_malformed_json(client, settings):
    assert_error(upload(client, b'{"meta": {"format_version": "4.10"}, "elements": ['), 400, "invalid_json")
    assert client.get("/v1/cosmetics").json()["total"] == 0
    assert not any(settings.storage_dir.iterdir()), "nothing may be stored for a rejected upload"


def test_rejects_unsupported_file_type(client, sample_bytes):
    assert_error(upload(client, sample_bytes, filename="hat.png"), 415, "unsupported_file_type")
    assert_error(upload(client, sample_bytes, filename="hat.bbmodel.exe"), 415, "unsupported_file_type")


def test_rejects_oversized_upload(client):
    big = b'{"meta": {"format_version": "4.10"}, "elements": [], "padding": "' + b"x" * (1024 * 1024) + b'"}'
    assert_error(upload(client, big), 413, "too_large")


def test_rejects_upload_just_over_limit_without_relying_on_headers(tmp_path, sample_bytes):
    # A small limit so the body is under the Content-Length guard: the route itself must still refuse it.
    settings = make_settings(tmp_path, max_upload_bytes=len(sample_bytes) - 1)
    with TestClient(create_app(settings)) as client:
        assert_error(upload(client, sample_bytes), 413, "too_large")


def test_rejects_empty_file(client):
    assert_error(upload(client, b""), 400, "empty_file")


def test_rejects_json_that_is_not_a_blockbench_project(client):
    assert_error(upload(client, b"[1, 2, 3]"), 400, "invalid_structure")
    assert_error(upload(client, b'{"hello": "world"}'), 400, "invalid_structure")
    assert_error(upload(client, b'{"meta": {"format_version": "4.10"}}'), 400, "invalid_structure")
    assert_error(upload(client, b'{"meta": {"format_version": "4.10"}, "elements": {}}'), 400, "invalid_structure")
    assert_error(upload(client, b'{"meta": {"format_version": "4.10"}, "elements": [], "x": NaN}'), 400, "invalid_json")


def test_rejects_deeply_nested_json(client):
    deep = b'{"meta": {"format_version": "4.10"}, "elements": [], "x": ' + b"[" * 100000 + b"]" * 100000 + b"}"
    assert_error(upload(client, deep), 400, "too_deep")


def test_rejects_duplicate_upload(client, sample_bytes):
    first = upload(client, sample_bytes).json()["id"]
    response = upload(client, sample_bytes)
    assert_error(response, 409, "duplicate")
    assert response.json()["error"]["details"] == {"id": first}


def test_uploaded_filename_is_never_used_as_a_path(client, settings, sample_bytes, tmp_path):
    response = upload(client, sample_bytes, filename="../../escape.bbmodel")
    assert response.status_code == 201
    assert response.json()["original_filename"] == "escape.bbmodel"
    assert not (tmp_path / "escape.bbmodel").exists()
    assert not (settings.storage_dir.parent / "escape.bbmodel").exists()


def test_control_characters_are_removed_from_names(client, sample_bytes):
    body = upload(client, sample_bytes, display_name="Hat\x00‮\n§c").json()
    assert body["display_name"] == "Hat§c"


# ---------------------------------------------------------------- missing cosmetics


def test_nonexistent_cosmetic(client):
    missing = "0" * 32
    assert_error(client.get(f"/v1/cosmetics/{missing}"), 404, "not_found")
    assert_error(client.get(f"/v1/cosmetics/{missing}/download"), 404, "not_found")
    assert_error(client.delete(f"/v1/cosmetics/{missing}"), 404, "not_found")
    assert_error(client.get("/v1/cosmetics/not-an-id"), 404, "not_found")
    assert_error(client.get("/v1/cosmetics/..%2F..%2Fmain.py/download"), 404, "not_found")


def test_unknown_route_uses_the_error_format(client):
    assert_error(client.get("/v1/nothing-here"), 404, "not_found")


def test_registered_cosmetic_with_missing_file(client, settings, sample_bytes):
    cosmetic_id = upload(client, sample_bytes).json()["id"]
    (settings.storage_dir / f"{cosmetic_id}.bbmodel").unlink()
    assert_error(client.get(f"/v1/cosmetics/{cosmetic_id}/download"), 404, "file_missing")


# ---------------------------------------------------------------- deletion and development mode


def test_delete_in_development(client, settings, sample_bytes):
    cosmetic_id = upload(client, sample_bytes).json()["id"]
    response = client.delete(f"/v1/cosmetics/{cosmetic_id}")
    assert response.status_code == 200
    assert response.json() == {"id": cosmetic_id, "deleted": True}
    assert_error(client.get(f"/v1/cosmetics/{cosmetic_id}"), 404, "not_found")
    assert not (settings.storage_dir / f"{cosmetic_id}.bbmodel").exists()


def test_upload_and_delete_are_refused_outside_development(tmp_path, sample_bytes):
    dev_settings = make_settings(tmp_path)
    with TestClient(create_app(dev_settings)) as dev:
        cosmetic_id = upload(dev, sample_bytes).json()["id"]
    with TestClient(create_app(make_settings(tmp_path, mode="read-only"))) as read_only:
        assert_error(upload(read_only, sample_bytes), 403, "read_only")
        assert_error(read_only.delete(f"/v1/cosmetics/{cosmetic_id}"), 403, "development_only")
        assert read_only.get(f"/v1/cosmetics/{cosmetic_id}/download").status_code == 200


def test_upload_and_delete_are_refused_from_other_computers(tmp_path, sample_bytes):
    settings = make_settings(tmp_path, dev_hosts=frozenset({"127.0.0.1"}))
    with TestClient(create_app(settings)) as client:  # reports itself as "testclient", not loopback
        assert_error(upload(client, sample_bytes), 403, "development_only")


# ---------------------------------------------------------------- persistence and cleanup


def test_data_survives_restart(tmp_path, sample_bytes):
    settings = make_settings(tmp_path)
    with TestClient(create_app(settings)) as first:
        cosmetic_id = upload(first, sample_bytes).json()["id"]
    with TestClient(create_app(settings)) as second:
        assert second.get(f"/v1/cosmetics/{cosmetic_id}").status_code == 200
        assert second.get(f"/v1/cosmetics/{cosmetic_id}/download").content == sample_bytes


def test_orphaned_files_are_removed_at_startup(tmp_path, sample_bytes):
    settings = make_settings(tmp_path)
    settings.storage_dir.mkdir(parents=True)
    orphan = settings.storage_dir / ("a" * 32 + ".bbmodel")
    interrupted = settings.storage_dir / ("." + "b" * 32 + ".tmp")
    unrelated = settings.storage_dir / "notes.txt"
    for path in (orphan, interrupted, unrelated):
        path.write_bytes(b"x")
    with TestClient(create_app(settings)):
        pass
    assert not orphan.exists()
    assert not interrupted.exists()
    assert unrelated.exists(), "files that are not Orbit's own are left alone"


def test_failed_registration_leaves_no_file(tmp_path, sample_bytes, monkeypatch):
    import models

    settings = make_settings(tmp_path)

    def broken_insert(connection, cosmetic):
        raise RuntimeError("database unavailable")

    monkeypatch.setattr(models, "insert", broken_insert)
    with TestClient(create_app(settings), raise_server_exceptions=False) as client:
        assert_error(upload(client, sample_bytes), 500, "internal_error")
        assert client.get("/v1/cosmetics").json()["total"] == 0
    assert not any(settings.storage_dir.iterdir())

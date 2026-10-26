"""Moderation and maintenance for the Orbit backend. Run on the server machine itself:

    python manage.py stats
    python manage.py list [--limit 50]
    python manage.py show <cosmetic id>
    python manage.py delete <cosmetic id>
    python manage.py block <player uuid> [--reason "spam"] [--days 7]
    python manage.py unblock <player uuid>
    python manage.py blocked
    python manage.py sign-out <player uuid>
    python manage.py import <file.bbmodel> [--name NAME] [--featured] [--author NAME] [--description TEXT]
    python manage.py feature <cosmetic id> [--author NAME] [--description TEXT]
    python manage.py unfeature <cosmetic id>
    python manage.py featured
    python manage.py reports [<player uuid>]
    python manage.py clear-reports <player uuid>
    python manage.py test-webhook
    python manage.py backup <directory>

Uses the same environment variables as the server (ORBIT_DATA_DIR, ORBIT_STORAGE_DIR, ...). With the systemd
service, load them first:  set -a; source ~/.config/orbit-backend.env; set +a
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sqlite3
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import config
import database
import models
from services.cosmetic_storage import MAX_NAME_LENGTH, CosmeticStorage, ValidationError, clean_filename, clean_text, validate_bbmodel
from services.discord import DiscordNotifier, ReportAlert, build_message


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Orbit backend moderation and maintenance")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("stats", help="Counts and storage use")
    list_parser = commands.add_parser("list", help="Newest cosmetics")
    list_parser.add_argument("--limit", type=int, default=50)
    commands.add_parser("show", help="All metadata of one cosmetic").add_argument("cosmetic_id")
    commands.add_parser("delete", help="Delete a cosmetic and its file").add_argument("cosmetic_id")
    block = commands.add_parser("block", help="Ban a player from Orbit Cloud: no uploads, featured cosmetics only")
    block.add_argument("player_uuid")
    block.add_argument("--reason", default="")
    block.add_argument("--days", type=float, default=None, help="Ban length; permanent if omitted")
    commands.add_parser("unblock", help="Allow a blocked player again").add_argument("player_uuid")
    commands.add_parser("blocked", help="List blocked players")
    commands.add_parser("sign-out", help="Invalidate all sessions of a player").add_argument("player_uuid")
    commands.add_parser("backup", help="Copy the database and stored files into a new folder").add_argument("directory")
    importer = commands.add_parser("import", help="Add a .bbmodel file from this machine (for example a featured cosmetic)")
    importer.add_argument("file")
    importer.add_argument("--name", default=None)
    importer.add_argument("--featured", action="store_true")
    importer.add_argument("--author", default=None)
    importer.add_argument("--description", default=None)
    feature = commands.add_parser("feature", help="Mark a cosmetic as checked and safe; it appears in the in-game catalog")
    feature.add_argument("cosmetic_id")
    feature.add_argument("--author", default=None)
    feature.add_argument("--description", default=None)
    commands.add_parser("unfeature", help="Remove a cosmetic from the catalog").add_argument("cosmetic_id")
    commands.add_parser("featured", help="List the featured catalog")
    commands.add_parser("reports", help="Reported players, or the reports on one player").add_argument("player_uuid", nargs="?")
    commands.add_parser("clear-reports", help="Delete all reports on a player").add_argument("player_uuid")
    commands.add_parser("test-webhook", help="Send a test message to the Discord webhook")
    args = parser.parse_args(argv)

    settings = config.Settings.from_env()
    database.init_database(settings.database_path)
    storage = CosmeticStorage(settings.storage_dir)
    storage.ensure_ready()
    connection = database.connect(settings.database_path)
    try:
        return COMMANDS[args.command](args, settings, storage, connection)
    finally:
        connection.close()


def _stats(args, settings, storage, connection) -> int:
    count = models.count(connection)
    size = models.total_size(connection)
    presence = connection.execute("SELECT COUNT(*) FROM presence WHERE updated_at >= ?", (time.time() - 90,)).fetchone()[0]
    sessions = connection.execute("SELECT COUNT(*) FROM sessions WHERE expires_at >= ?", (time.time(),)).fetchone()[0]
    print(f"mode:              {settings.mode}")
    print(f"database:          {settings.database_path}")
    print(f"storage:           {settings.storage_dir}")
    print(f"cosmetics:         {count} ({size / 1024 / 1024:.1f} MiB of {settings.max_storage_bytes / 1024 / 1024:.0f} MiB)")
    print(f"players online:    {presence}")
    print(f"active sessions:   {sessions}")
    with connection:
        bans = models.blocked_players(connection)
    print(f"banned players:    {len(bans)}")
    print(f"featured:          {models.count_featured(connection)}")
    print(f"reported players:  {len(models.reported_players(connection))}")
    print(f"discord webhook:   {'configured' if DiscordNotifier(settings.discord_webhook_url).enabled else 'not configured'}")
    return 0


def _list(args, settings, storage, connection) -> int:
    for cosmetic in models.list_all(connection, max(1, args.limit), 0):
        print(f"{cosmetic.id}  {cosmetic.uploaded_at}  {cosmetic.size_bytes:>8} B  owner={cosmetic.owner_uuid or '-'}  {cosmetic.display_name}")
    return 0


def _show(args, settings, storage, connection) -> int:
    cosmetic = models.get(connection, args.cosmetic_id)
    if cosmetic is None:
        print("No cosmetic with this ID.", file=sys.stderr)
        return 1
    for key, value in cosmetic.__dict__.items():
        print(f"{key:18} {value}")
    return 0


def _delete(args, settings, storage, connection) -> int:
    cosmetic = models.get(connection, args.cosmetic_id)
    if cosmetic is None:
        print("No cosmetic with this ID.", file=sys.stderr)
        return 1
    with connection:
        models.delete(connection, cosmetic.id)
    storage.delete(cosmetic.stored_filename)
    print(f"Deleted {cosmetic.id} ({cosmetic.display_name}).")
    return 0


def _uuid(value: str) -> str:
    import uuid

    try:
        return str(uuid.UUID(value))
    except ValueError:
        raise SystemExit(f"Not a UUID: {value}") from None


def _when(timestamp: float | None) -> str:
    return "never" if timestamp is None else datetime.fromtimestamp(timestamp, timezone.utc).isoformat(timespec="minutes")


def _block(args, settings, storage, connection) -> int:
    player = _uuid(args.player_uuid)
    now = time.time()
    expires = now + args.days * 86400 if args.days else None
    with connection:
        models.block_player(connection, player, args.reason, now, expires)
        # Their cosmetics disappear at once; their client then shares featured cosmetics only.
        models.delete_presence(connection, player)
    print(f"Banned {player} until {_when(expires)}.")
    return 0


def _unblock(args, settings, storage, connection) -> int:
    player = _uuid(args.player_uuid)
    with connection:
        found = models.unblock_player(connection, player)
    print(f"Unblocked {player}." if found else f"{player} was not blocked.")
    return 0


def _blocked(args, settings, storage, connection) -> int:
    with connection:
        bans = models.blocked_players(connection)
    for ban in bans:
        print(f"{ban.player_uuid}  since {_when(ban.blocked_at)}  until {_when(ban.expires_at)}  [{ban.source}]  {ban.reason}")
    if not bans:
        print("No banned players.")
    return 0


def _sign_out(args, settings, storage, connection) -> int:
    player = _uuid(args.player_uuid)
    with connection:
        removed = models.delete_player_sessions(connection, player)
    print(f"Removed {removed} session(s).")
    return 0


def _backup(args, settings, storage, connection) -> int:
    target = Path(args.directory) / datetime.now().strftime("orbit-backup-%Y%m%d-%H%M%S")
    target.mkdir(parents=True)
    # SQLite's online backup is consistent even while the server is running.
    destination = sqlite3.connect(target / "orbit.sqlite3")
    with destination:
        connection.backup(destination)
    destination.close()
    shutil.copytree(settings.storage_dir, target / "cosmetics", ignore=shutil.ignore_patterns(".*.tmp"))
    print(f"Backup written to {target}")
    return 0


def _import(args, settings, storage, connection) -> int:
    path = Path(args.file)
    data = path.read_bytes()
    if len(data) > settings.max_upload_bytes:
        print(f"Larger than the {settings.max_upload_bytes // 1024} KiB limit.", file=sys.stderr)
        return 1
    try:
        validated = validate_bbmodel(data)
    except ValidationError as error:
        print(f"Not accepted: {error.message}", file=sys.stderr)
        return 1
    # The mod identifies cosmetics by the SHA-256 of self-contained files; external textures would change it.
    textures = json.loads(data).get("textures") or []
    if any(isinstance(t, dict) and not t.get("source") for t in textures):
        print("Some textures are not embedded. Save the model in Blockbench with textures embedded, then import it again.", file=sys.stderr)
        return 1
    sha256 = hashlib.sha256(data).hexdigest()
    if models.find_by_sha256(connection, sha256) is not None:
        print("This file is already on the server.", file=sys.stderr)
        return 1
    import uuid

    cosmetic_id = uuid.uuid4().hex
    original = clean_filename(path.name)
    now_iso = datetime.now(timezone.utc).isoformat(timespec="seconds")
    stored = storage.save(cosmetic_id, data)
    cosmetic = models.Cosmetic(
        id=cosmetic_id,
        display_name=clean_text(args.name, MAX_NAME_LENGTH) if args.name else validated.name or original.rsplit(".", 1)[0],
        original_filename=original,
        stored_filename=stored,
        size_bytes=len(data),
        sha256=sha256,
        uploaded_at=now_iso,
        validation_status=models.STATUS_APPROVED,
        format_version=validated.format_version,
        model_format=validated.model_format,
        element_count=validated.element_count,
        texture_count=validated.texture_count,
        animation_count=validated.animation_count,
        featured=1 if args.featured else 0,
        featured_at=now_iso if args.featured else None,
        author=clean_text(args.author, MAX_NAME_LENGTH) if args.author else None,
        description=clean_text(args.description, 300) if args.description else None,
    )
    try:
        with connection:
            models.insert(connection, cosmetic)
    except BaseException:
        storage.delete(stored)
        raise
    print(f"Imported {cosmetic_id} ({cosmetic.display_name}){' as featured' if args.featured else ''}.")
    return 0


def _feature(args, settings, storage, connection) -> int:
    author = clean_text(args.author, MAX_NAME_LENGTH) if args.author else None
    description = clean_text(args.description, 300) if args.description else None
    with connection:
        found = models.set_featured(connection, args.cosmetic_id, True, datetime.now(timezone.utc).isoformat(timespec="seconds"), author, description)
    if not found:
        print("No cosmetic with this ID.", file=sys.stderr)
        return 1
    print(f"Featured {args.cosmetic_id}. Only feature cosmetics you checked yourself.")
    return 0


def _unfeature(args, settings, storage, connection) -> int:
    with connection:
        found = models.set_featured(connection, args.cosmetic_id, False, "")
    print(f"Removed {args.cosmetic_id} from the catalog." if found else "No cosmetic with this ID.")
    return 0 if found else 1


def _featured(args, settings, storage, connection) -> int:
    items = models.list_featured(connection, 200, 0)
    for cosmetic in items:
        print(f"{cosmetic.id}  {cosmetic.sha256[:16]}  {cosmetic.display_name}  by {cosmetic.author or '-'}")
    if not items:
        print("The catalog is empty. Add cosmetics with: manage.py import <file> --featured")
    return 0


def _reports(args, settings, storage, connection) -> int:
    with connection:
        models.prune_reports(connection, time.time() - settings.report_retention_days * 86400)
    if args.player_uuid:
        reports = models.reports_for(connection, _uuid(args.player_uuid))
        for report in reports:
            print(f"{_when(report.created_at)}  by {report.reporter_uuid}  {report.reason}  {report.note}")
        if not reports:
            print("No reports on this player.")
        return 0
    rows = models.reported_players(connection)
    for player, name, reporters, latest in rows:
        print(f"{player}  {name:16}  {reporters} reporter(s), latest {_when(latest)}")
    if not rows:
        print("No reports in the last {} days.".format(settings.report_retention_days))
    return 0


def _clear_reports(args, settings, storage, connection) -> int:
    with connection:
        removed = models.clear_reports(connection, _uuid(args.player_uuid))
    print(f"Deleted {removed} report(s).")
    return 0


def _test_webhook(args, settings, storage, connection) -> int:
    notifier = DiscordNotifier(settings.discord_webhook_url, allow_local=settings.dev_mode)
    if not notifier.enabled:
        print("Set ORBIT_DISCORD_WEBHOOK_URL to the webhook URL first.", file=sys.stderr)
        return 1
    alert = ReportAlert("00000000-0000-0000-0000-000000000000", "TestPlayer", settings.report_alert_threshold, {"other": 1}, ["This is a test message."])
    notifier._post(build_message(alert))
    print("Sent a test message; check the Discord channel.")
    return 0


COMMANDS = {
    "stats": _stats,
    "list": _list,
    "show": _show,
    "delete": _delete,
    "block": _block,
    "unblock": _unblock,
    "blocked": _blocked,
    "sign-out": _sign_out,
    "backup": _backup,
    "import": _import,
    "feature": _feature,
    "unfeature": _unfeature,
    "featured": _featured,
    "reports": _reports,
    "clear-reports": _clear_reports,
    "test-webhook": _test_webhook,
}

if __name__ == "__main__":
    sys.exit(main())

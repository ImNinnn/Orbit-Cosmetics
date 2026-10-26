# Orbit Cosmetics Backend

A small FastAPI service that stores Blockbench `.bbmodel` cosmetics and serves them back. It is the first step
towards the Orbit cosmetics backend: upload, catalogue, metadata and download, running only on your own
computer. The Orbit client mod also uses it as a fallback on Minecraft servers **without** the Orbit server mod
or plugin, so players can still see each other's cosmetics there (see [Presence](#presence-used-by-the-orbit-client-mod)).
Servers with the Orbit mod or plugin never use the backend.

The server always listens on `127.0.0.1` only. It runs in one of three modes (`ORBIT_MODE`):

- **development** (default): no accounts; upload, delete and presence work only for requests made on this
  computer. For local testing. Never put this mode behind a tunnel or proxy.
- **production**: the public deployment at `https://api.orbitcosmetics.qd.je`, reached through Cloudflare
  Tunnel. Writing needs a Minecraft account verified with Mojang ([Sign-in](#sign-in-production)); nothing can
  be deleted over HTTP; rate limits, quotas and a storage cap apply. See
  **[deploy/DEPLOYMENT.md](deploy/DEPLOYMENT.md)** for the step-by-step setup.
- **read-only**: serves the catalogue and presence, refuses all writes.

- Python 3.11+ (tested with Python 3.14 on Fedora 44), FastAPI, SQLite, local files
- No Docker; no passwords, API secrets or Microsoft tokens are ever stored

## Project layout

```text
backend/
├── main.py                       App factory, startup (tables, orphan cleanup), request size guard
├── config.py                     Settings from environment variables (paths, limits, mode)
├── auth.py                       Who is asking: Mojang session check, sessions, proxy headers, rate limiter
├── manage.py                     Moderation and maintenance on the server machine (delete, block, backup)
├── discord_bot.py                Discord moderation bot (prefix commands: bans and reports)
├── .env                          Secrets such as the bot token (TOKEN=...); never committed
├── database.py                   SQLite connections and table creation
├── models.py                     Table schema and parameterized queries
├── schemas.py                    JSON response models (shown in /docs)
├── errors.py                     One JSON error format for every failure
├── routes/
│   ├── health.py                 GET /, GET /v1/health
│   ├── auth.py                   Sign-in (production)
│   ├── cosmetics.py              Catalogue, upload, download, delete, lookup by SHA-256
│   ├── presence.py               What each player wears (used by the client mod on non-Orbit servers)
│   ├── featured.py               The featured catalog (cosmetics checked by hand)
│   └── reports.py                Player reports, Discord alerts and automatic bans
├── services/
│   ├── cosmetic_storage.py       .bbmodel validation and file storage
│   ├── equip.py                  Reads the worn cosmetics out of an Orbit Equip message
│   ├── moderation.py             Bans, reports and player lookup used by the Discord bot
│   └── discord.py                Discord webhook messages for moderators
├── samples/sample_hat.bbmodel    A tiny valid model for manual testing
├── storage/cosmetics/            Stored files (<id>.bbmodel), created automatically
├── data/                         SQLite database (orbit.sqlite3), created automatically
├── deploy/                       Public deployment: DEPLOYMENT.md, env template, systemd and tunnel installers
├── tests/                        pytest suite (uses temporary folders only)
├── requirements.txt
└── pytest.ini
```

`config.py` and `errors.py` are additions to the suggested layout: the first lets tests point the app at
temporary folders, the second keeps every error response in the same shape.

## Setup on Fedora Linux

### 1. Get to the project directory

The backend lives in `backend/` inside the Orbit project. If you are creating it from scratch:

```bash
mkdir -p ~/Orbit
cd ~/Orbit
# copy or clone the backend/ folder here, then:
cd backend
```

If you already have the Orbit project, just `cd` into its `backend` folder.

### 2. Create and activate a virtual environment

Fedora ships Python 3 with the `venv` module. If `python3` is missing: `sudo dnf install python3`.

```bash
python3 -m venv .venv
source .venv/bin/activate
```

Your prompt now starts with `(.venv)`. Run `source .venv/bin/activate` again in every new terminal.

### 3. Install dependencies

```bash
pip install --upgrade pip
pip install -r requirements.txt
```

### 4. Start the server

```bash
python main.py
```

Expected output ends with:

```text
WARNING orbit: Development mode: upload and delete are enabled for requests from this computer.
INFO:     Uvicorn running on http://127.0.0.1:8000 (Press CTRL+C to quit)
```

The database (`data/orbit.sqlite3`) and storage folder are created automatically. Stop the server with
`Ctrl+C`. During development you can also use `uvicorn main:app --host 127.0.0.1 --port 8000 --reload` to
restart automatically when code changes.

### 5. Open the API documentation

Open <http://127.0.0.1:8000/docs> in a browser. Every endpoint is listed there with its parameters,
responses and a **Try it out** button.

## Manual testing

Keep the server running in one terminal and use a second terminal (inside `backend/`) for the commands.

### 6. Try each endpoint in Swagger UI

In <http://127.0.0.1:8000/docs>: expand an endpoint, click **Try it out**, fill in the fields and click
**Execute**. For the upload endpoint, choose a file in the `file` field.

The same checks from the terminal:

```bash
curl http://127.0.0.1:8000/
curl http://127.0.0.1:8000/v1/health
curl http://127.0.0.1:8000/v1/cosmetics
```

### 7. Upload a sample .bbmodel

```bash
curl -F "file=@samples/sample_hat.bbmodel" -F "display_name=Sample hat" http://127.0.0.1:8000/v1/cosmetics/upload
```

Any Blockbench project works too, for example the Orbit demo model:
`curl -F "file=@../examples/orbit_demo.bbmodel" http://127.0.0.1:8000/v1/cosmetics/upload`.

Copy the `id` from the response:

```bash
ID=<paste the id here>
```

### 8. Check the catalogue and metadata

```bash
curl http://127.0.0.1:8000/v1/cosmetics
curl http://127.0.0.1:8000/v1/cosmetics/$ID
```

### 9. Download it and compare with the original

```bash
curl -o /tmp/downloaded.bbmodel http://127.0.0.1:8000/v1/cosmetics/$ID/download
cmp samples/sample_hat.bbmodel /tmp/downloaded.bbmodel && echo "identical"
sha256sum samples/sample_hat.bbmodel   # matches the "sha256" in the metadata
```

### 10. Restart and confirm the cosmetic is still there

Press `Ctrl+C` in the server terminal, run `python main.py` again, then:

```bash
curl http://127.0.0.1:8000/v1/cosmetics/$ID
```

Optionally delete it (development only):

```bash
curl -X DELETE http://127.0.0.1:8000/v1/cosmetics/$ID
```

### 11. Run the automated tests

```bash
python -m pytest
```

Expected: `76 passed`. The tests create their own temporary database and storage folders, so they never touch
`data/` or `storage/cosmetics/`. Use `python -m pytest -v` to see each test name.

## API reference

All errors use the same shape:

```json
{"error": {"code": "not_found", "message": "No cosmetic with this ID exists."}}
```

Some errors add `"details"` (for example the existing ID for a duplicate upload, or the invalid fields of a
422). Cosmetic IDs are 32 lowercase hexadecimal characters generated by the server.

### GET /

```bash
curl http://127.0.0.1:8000/
```

`200`:

```json
{"name": "Orbit Cosmetics Backend", "version": "0.2.0", "status": "ok", "environment": "development", "docs": "/docs"}
```

`environment` is the mode: `development`, `production` or `read-only`.

### GET /v1/health

```bash
curl http://127.0.0.1:8000/v1/health
```

`200`:

```json
{"status": "ok", "database": "ok", "storage": "ok", "mode": "development", "auth": "none"}
```

`auth` is `mojang` in production mode: writes then need a [session token](#sign-in-production).

`status` is `degraded` (and the failing part `error`) if the database cannot be queried or the storage folder
is not writable.

### GET /v1/cosmetics

Query parameters: `limit` (1-200, default 50), `offset` (default 0). Newest first.

```bash
curl "http://127.0.0.1:8000/v1/cosmetics?limit=50&offset=0"
```

`200` (empty catalogue):

```json
{"items": [], "total": 0, "limit": 50, "offset": 0}
```

`200` (with a cosmetic): `items` contains objects like the one below. `422 invalid_request` for out-of-range
`limit`/`offset`.

### POST /v1/cosmetics/upload

Multipart form with `file` (required, `.bbmodel` or `.json`, at most 1 MiB) and `display_name` (optional;
defaults to the model's own name, then the file name). In production, send `Authorization: Bearer <token>`;
the cosmetic's `owner_uuid` is then the signed-in player (it is `null` for development uploads).

```bash
curl -F "file=@samples/sample_hat.bbmodel" -F "display_name=Sample hat" http://127.0.0.1:8000/v1/cosmetics/upload
```

`201`:

```json
{
  "id": "cdb73d1960ea458d993febb8b23baf50",
  "display_name": "Sample hat",
  "original_filename": "sample_hat.bbmodel",
  "size_bytes": 766,
  "sha256": "57d80c5a92159fe98e377241f5b1ba6eb98a1550c5f12b0f204ee3b8d69fe51f",
  "uploaded_at": "2026-10-25T15:25:13+00:00",
  "validation_status": "approved",
  "format_version": "4.10",
  "model_format": "free",
  "element_count": 1,
  "texture_count": 0,
  "animation_count": 0,
  "download_url": "/v1/cosmetics/cdb73d1960ea458d993febb8b23baf50/download"
}
```

Errors:

| Status | `code` | When |
|---|---|---|
| 400 | `empty_file` | The file is empty |
| 400 | `invalid_json`, `invalid_encoding`, `too_deep` | Not UTF-8 JSON, or nested absurdly deep |
| 400 | `invalid_structure`, `too_complex` | Not a Blockbench project, or too many elements/textures/animations |
| 401 | `not_authenticated` | Production: no valid session token |
| 403 | `development_only` | Development: the request is not from this computer |
| 403 | `read_only`, `blocked` | Read-only mode; or the player was blocked with `manage.py block` |
| 409 | `duplicate` | The exact same file was already uploaded (`details.id` is the existing cosmetic) |
| 413 | `too_large` | Larger than 1 MiB |
| 415 | `unsupported_file_type` | Not a `.bbmodel` or `.json` file |
| 422 | `invalid_request` | The `file` field is missing |
| 429 | `quota_exceeded` | Production: more than `ORBIT_UPLOADS_PER_PLAYER_PER_DAY` uploads in 24 hours |
| 507 | `storage_full` | All stored files together would exceed `ORBIT_MAX_STORAGE_MB` |

Example: `{"error": {"code": "invalid_json", "message": "The file is not valid JSON: Expecting value: line 1 column 10 (char 9)"}}`

### GET /v1/cosmetics/{cosmetic_id}

```bash
curl http://127.0.0.1:8000/v1/cosmetics/cdb73d1960ea458d993febb8b23baf50
```

`200`: the same object as the upload response. `404 not_found` for unknown or malformed IDs.

### GET /v1/cosmetics/{cosmetic_id}/download

```bash
curl -OJ http://127.0.0.1:8000/v1/cosmetics/cdb73d1960ea458d993febb8b23baf50/download
```

`200`: the stored file, byte for byte, with headers:

```text
content-type: application/json
content-disposition: attachment; filename="sample_hat.bbmodel"
x-orbit-sha256: 57d80c5a92159fe98e377241f5b1ba6eb98a1550c5f12b0f204ee3b8d69fe51f
x-content-type-options: nosniff
```

`404 not_found` for unknown IDs, `404 file_missing` if the entry exists but the file was removed from disk,
`409 not_approved` if the cosmetic is not approved (reserved for the future review workflow).

### DELETE /v1/cosmetics/{cosmetic_id} (development only)

```bash
curl -X DELETE http://127.0.0.1:8000/v1/cosmetics/cdb73d1960ea458d993febb8b23baf50
```

`200`:

```json
{"id": "cdb73d1960ea458d993febb8b23baf50", "deleted": true}
```

`403 development_only` outside development mode or from another computer, `404 not_found` for unknown IDs.
In production, cosmetics are deleted with `manage.py delete <id>` on the server machine.

### GET /v1/cosmetics/by-sha256/{sha256}

Looks a cosmetic up by the SHA-256 of its file, which is the asset ID the Orbit mod uses.

```bash
curl http://127.0.0.1:8000/v1/cosmetics/by-sha256/57d80c5a92159fe98e377241f5b1ba6eb98a1550c5f12b0f204ee3b8d69fe51f
```

`200`: the same object as the upload response. `404 not_found` if no cosmetic has this hash.

### GET /v1/cosmetics/by-sha256/{sha256}/download

```bash
curl -O http://127.0.0.1:8000/v1/cosmetics/by-sha256/57d80c5a92159fe98e377241f5b1ba6eb98a1550c5f12b0f204ee3b8d69fe51f/download
```

`200`: the file, with the same headers as the download by ID. `404 not_found` otherwise.

### Presence (used by the Orbit client mod)

On a Minecraft server without the Orbit server mod or plugin, each Orbit client reports what its player wears,
and asks what the other players in the tab list wear. Entries expire 90 seconds after the last report; clients
refresh every 30 seconds and remove their entry when they leave.

`equip` is the base64 of an Orbit protocol *Equip* message (`docs/PROTOCOL.md` in the mod project): the worn
cosmetics by SHA-256 plus their animation and physics settings. The backend stores it as-is (at most 32,000
decoded bytes); clients decode and validate it with the same strict decoder they use for server traffic.

#### PUT /v1/presence/{player_uuid}

```bash
curl -X PUT http://127.0.0.1:8000/v1/presence/069a79f4-44e9-4726-a5be-fca90e38aaf5 \
  -H 'Content-Type: application/json' \
  -d '{"player_name": "Alice", "protocol": 2, "equip": "AgA="}'
```

`200`:

```json
{"player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "ttl_seconds": 90}
```

`400 invalid_uuid`, `invalid_name` (1-16 letters, digits, underscores) or `invalid_equip` (not base64, empty or
too large); `403 development_only` / `read_only` / `blocked`; production: `401 not_authenticated`, and
`403 not_your_player` unless `player_uuid` is the signed-in player. In production the stored name is always the
Mojang-verified one, whatever `player_name` says.

#### POST /v1/presence/query

```bash
curl http://127.0.0.1:8000/v1/presence/query -H 'Content-Type: application/json' \
  -d '{"players": ["069a79f4-44e9-4726-a5be-fca90e38aaf5", "853c80ef-3c37-49fd-aa49-938b674adae6"]}'
```

`200` (only players with a fresh entry are returned; at most 200 UUIDs per query). A `names` list can be sent
as well: offline-mode servers give players UUIDs that differ from their real ones, so the mod also looks them up
by name.

```json
{
  "players": [
    {"player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "player_name": "Alice", "protocol": 2, "equip": "AgA=", "updated_at": 1793028913.4}
  ],
  "ttl_seconds": 90
}
```

#### DELETE /v1/presence/{player_uuid}

```bash
curl -X DELETE http://127.0.0.1:8000/v1/presence/069a79f4-44e9-4726-a5be-fca90e38aaf5
```

`200` with the same body as the PUT, `404 not_found` if nothing is stored; same permission errors as the PUT.

### Sign-in (production)

Uses the same check as online-mode Minecraft servers. The game's Microsoft/Mojang access token goes only to
Mojang; this backend never receives it. The Orbit mod does all of this automatically.

1. `POST /v1/auth/challenge` → `{"server_id": "<40 hex>", "expires_in": 120}`
2. The game calls Mojang's join endpoint with that `server_id` (in the mod:
   `sessionService().joinServer(profileId, accessToken, serverId)`).
3. `POST /v1/auth/session` with `{"username": "Steve", "server_id": "<same>"}`. The backend asks
   `sessionserver.mojang.com/session/minecraft/hasJoined`; if Mojang confirms:

   ```json
   {"token": "...", "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "player_name": "Steve", "expires_at": 1793115313.0,
    "banned": false, "banned_until": null}
   ```

   `401 invalid_challenge` (unknown, used or expired `server_id`), `401 not_authenticated` (Mojang didn't
   confirm), `502 mojang_unavailable`.
4. Send `Authorization: Bearer <token>` with uploads and presence updates. Tokens last `ORBIT_SESSION_HOURS`
   (24); only their SHA-256 is stored.

`GET /v1/auth/me` returns the session's player (without the token); `DELETE /v1/auth/session` signs out.

Rate limits apply to every request per visitor IP: `ORBIT_REQUESTS_PER_MINUTE` (240) in general and
`ORBIT_AUTH_REQUESTS_PER_MINUTE` (10) for `/v1/auth/*`. Over the limit: `429 too_many_requests` with
`Retry-After: 60`.

### Featured catalog

`GET /v1/featured?limit=50&offset=0` lists cosmetics the operator checked by hand and marked as featured
(newest first), in the same shape as the catalogue plus `featured`, `author` and `description`. The mod shows
them in its **Catalog** screen and downloads them through `/v1/cosmetics/by-sha256/{sha256}/download`.
Featured cosmetics are added with `manage.py import <file> --featured` or `manage.py feature <id>`; imports must
have their textures embedded, so the file's SHA-256 is the same ID the mod gives it.

### Bans

A banned player (by the operator with `manage.py block`, or automatically after reports) cannot upload or
report, and their presence may only contain featured cosmetics: anything else gets
`403 blocked` with `details.expires_at` (Unix time, `null` for permanent bans). Sign-in and `/v1/auth/me` include
`banned` and `banned_until`, so the mod can tell the player and keep sharing their featured cosmetics. Bans with
an end date lift themselves. In development mode, which has no accounts, bans apply to the presence UUID.

### POST /v1/reports

Reports a player from the mod's Players menu. Signed-in players only (in development mode, `reporter_uuid` in
the body names the reporter instead).

```bash
curl http://127.0.0.1:8000/v1/reports -H 'Content-Type: application/json' -d '{"player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "player_name": "Alice", "reason": "inappropriate", "note": "offensive hat", "cosmetics": ["<sha256>"],
  "reporter_uuid": "853c80ef-3c37-49fd-aa49-938b674adae6"}'
```

`201` `{"received": true, "new": true}` (`new` is false when the same reporter reported this player again this
week; the report is updated, not counted twice). Reasons: `inappropriate`, `offensive`, `lag`, `impersonation`,
`other`. Errors: `400 invalid_reason` / `invalid_uuid` / `invalid_name` / `cannot_report_self`, `401
not_authenticated`, `403 blocked`, `429 report_limit` (`ORBIT_REPORTS_PER_PLAYER_PER_DAY`, 20).

What happens next:

- Reports are kept for `ORBIT_REPORT_RETENTION_DAYS` (7 days), then dropped.
- When `ORBIT_REPORT_ALERT_THRESHOLD` (3) **different** players have reported someone, one message goes to the
  Discord webhook: player name, UUID, the reasons, reporters' notes, and their current cosmetics (from their
  presence, or what the reporters saw), with the cosmetic IDs needed for `manage.py delete`.
- At `ORBIT_REPORT_BAN_THRESHOLD` (6) different reporters, the player is banned for `ORBIT_REPORT_BAN_DAYS`
  (7 days), their cosmetics disappear at once, and a second Discord message says so.

The webhook URL comes from `ORBIT_DISCORD_WEBHOOK_URL` only; it is a secret, so keep it out of code and Git.
Only `https://discord.com/api/webhooks/...` addresses are accepted (plus a local test receiver in development
mode). Messages never ping anyone (`allowed_mentions` is empty) and player text is escaped.
`python manage.py test-webhook` sends one test message.

## Moderation (manage.py)

Run on the server machine with the same environment as the server (for the systemd service:
`set -a; source ~/.config/orbit-backend.env; set +a`):

```bash
python manage.py stats                      # counts and storage use
python manage.py list --limit 20            # newest cosmetics
python manage.py show <cosmetic_id>         # all metadata, including owner_uuid
python manage.py delete <cosmetic_id>       # removes the entry and its file
python manage.py block <player_uuid> --reason "spam" --days 7   # ban (permanent without --days)
python manage.py unblock <player_uuid>      # also lifts automatic bans
python manage.py blocked                    # bans in force, with end date and source (manual/reports)
python manage.py sign-out <player_uuid>     # invalidates all their sessions
python manage.py import hat.bbmodel --featured --author "Orbit Team" --description "A hat"
python manage.py feature <cosmetic_id>      # or unfeature; featured lists the catalog
python manage.py reports [<player_uuid>]    # reported players, or the reports on one player
python manage.py clear-reports <player_uuid>
python manage.py test-webhook               # one test message to the Discord webhook
python manage.py backup ~/orbit-backups     # consistent copy of the database and files
```

## Discord moderation bot

`discord_bot.py` manages bans and reports from Discord with prefix commands:

| Command | |
|---|---|
| `o!help` | The command list |
| `o!bans` | Players banned right now, with end date and source (manual, discord, reports) |
| `o!ban <player> <duration> [reason]` | Ban from Orbit Cloud; duration `7d`, `12h`, `2w` or `perm` |
| `o!unban <player>` | Lift a ban, including automatic ones |
| `o!player <player>` | Ban, reports and current cosmetics |
| `o!reports [player]` | Reported players, or the reports on one player |
| `o!clearreports <player>` | Delete the reports on a player |

`<player>` is a Minecraft name or UUID; names the backend has never seen are looked up with Mojang.

**Who can use it.** Commands only work in the Discord server `1514172105787183114`
(`ORBIT_DISCORD_GUILD_ID`), and only for someone who passes **both** checks: they own that Discord server, and
they own the bot application (for an application owned by a team, the team owner). Anyone else in that server
gets a short refusal; anywhere else (other servers, DMs) the bot stays silent, and it leaves any other server it
is added to.

**Setup.**

1. The token goes in `backend/.env` as `TOKEN=<token>` (or the `ORBIT_DISCORD_BOT_TOKEN` variable). Keep the file
   private (`chmod 600 .env`); it is in `.gitignore`. Real environment variables override `.env`.
2. In the [Discord Developer Portal](https://discord.com/developers/applications) > your application > **Bot**,
   turn on **Message Content Intent**. Prefix commands cannot be read without it.
3. Invite the bot to the server with the permissions *View Channels*, *Send Messages*, *Embed Links* and
   *Read Message History*:
   `https://discord.com/oauth2/authorize?client_id=<application id>&scope=bot&permissions=84992`
4. Run it next to the API, with the same environment (it shares the SQLite database):

   ```bash
   python discord_bot.py
   ```

   `deploy/install-service.sh` installs it as `orbit-bot.service` when `.env` has a token. On hosts that allow a
   single start command, set `ORBIT_DISCORD_BOT=1` instead: the API process then runs the bot too.

Change the prefix with `ORBIT_DISCORD_BOT_PREFIX`. Every action is logged with the Discord user who did it.

## Configuration

Environment variables (all optional):

| Variable | Default | Meaning |
|---|---|---|
| `ORBIT_DATA_DIR` | `backend/data` | Folder of the default database file |
| `ORBIT_DATABASE_PATH` | `$ORBIT_DATA_DIR/orbit.sqlite3` | SQLite database file |
| `ORBIT_STORAGE_DIR` | `backend/storage/cosmetics` | Where uploaded files are stored |
| `ORBIT_MAX_UPLOAD_BYTES` | `1048576` (1 MiB) | Upload size limit |
| `ORBIT_MODE` | `development` | `development`, `production` or `read-only` (see the top of this file) |
| `ORBIT_DEV_MODE` | `1` | Old switch from the first prototype; `0` means `read-only` when `ORBIT_MODE` is unset |
| `ORBIT_CORS_ORIGINS` | none | Comma-separated web origins allowed to *read* the API from a browser |
| `ORBIT_TRUST_PROXY_HEADERS` | `1` | Take the visitor IP from `CF-Connecting-IP` / `X-Forwarded-For`, only for requests from this machine |
| `ORBIT_SESSION_HOURS` | `24` | Session token lifetime |
| `ORBIT_UPLOADS_PER_PLAYER_PER_DAY` | `20` | Production upload quota |
| `ORBIT_MAX_STORAGE_MB` | `1024` | Total size of all stored files |
| `ORBIT_REQUESTS_PER_MINUTE` | `240` | Per-IP request limit |
| `ORBIT_AUTH_REQUESTS_PER_MINUTE` | `10` | Per-IP limit for `/v1/auth/*` |
| `ORBIT_DISCORD_WEBHOOK_URL` | none | Discord webhook for report alerts and automatic bans (secret) |
| `ORBIT_REPORT_RETENTION_DAYS` | `7` | Reports are dropped after this many days |
| `ORBIT_REPORT_ALERT_THRESHOLD` | `3` | Different reporters before moderators are alerted |
| `ORBIT_REPORT_BAN_THRESHOLD` | `6` | Different reporters before an automatic ban |
| `ORBIT_REPORT_BAN_DAYS` | `7` | Length of automatic bans |
| `ORBIT_REPORTS_PER_PLAYER_PER_DAY` | `20` | Reports one player can send per day |
| `TOKEN` / `ORBIT_DISCORD_BOT_TOKEN` | none | Discord bot token (secret; normally in `backend/.env`) |
| `ORBIT_DISCORD_GUILD_ID` | `1514172105787183114` | The only Discord server where the bot works |
| `ORBIT_DISCORD_BOT_PREFIX` | `o!` | Prefix of the bot's commands |
| `ORBIT_DISCORD_BOT` | `0` | `1` runs the bot inside the API process |

Settings can also be put in `backend/.env` (`KEY=value` lines); real environment variables take precedence.

Example: `ORBIT_MODE=read-only python main.py` serves the existing catalogue without allowing changes. The
production values are in [deploy/orbit-backend.env.example](deploy/orbit-backend.env.example).

## How uploads are handled

1. The request is refused with `413` before its body is read if `Content-Length` is far above the limit.
2. Only `.bbmodel` and `.json` file names are accepted. The name is reduced to its base name and cleaned,
   and is kept as metadata only. It is **never** used as a path.
3. At most 1 MiB is read; anything longer is rejected.
4. The bytes are parsed as JSON (UTF-8, `NaN`/`Infinity` rejected, absurd nesting rejected) and checked for
   the basic Blockbench structure: a JSON object with a `meta` section containing `format_version`, and
   `elements` and/or an `outliner`, all of the right types and within count limits. Nothing in the file is
   executed or evaluated: Molang expressions, scripts and plugin data are just text to this server.
5. A random ID is generated and the file is written atomically as `storage/cosmetics/<id>.bbmodel`
   (temporary file, `fsync`, rename; readable by your user only).
6. Only then is the metadata inserted into SQLite in a transaction, with status `approved`. If that fails,
   the stored file is deleted again, so a cosmetic is never downloadable unless both steps succeeded.
7. At every start, interrupted temporary files and stored files without a database entry are removed. Files in
   the storage folder that do not follow Orbit's naming scheme are left alone.

Identical files are detected by SHA-256 and refused with `409`. The SHA-256 is the same value the Orbit mod
uses to identify a cosmetic, which will make linking the two straightforward later.

## Known limitations

- **Validation is structural only.** A file that passes is a JSON Blockbench project with the expected shape.
  It is not guaranteed to be safe to render, performant, or compatible with Orbit's renderer: textures are not
  decoded, geometry is not checked against Orbit's limits, and anchors and animations are not inspected.
  The Orbit mod and server still validate every model fully before using it.
- Accounts are Minecraft accounts only, checked through Mojang. Offline-mode ("cracked") players can view
  cosmetics but never publish. If Mojang's session server is down, nobody can sign in until it's back
  (existing sessions keep working).
- Development mode trusts anything from this computer. It refuses requests carrying proxy headers, but it must
  still never run behind a tunnel or proxy: use `production` there.
- Every uploaded cosmetic is approved immediately; there is no review workflow yet (the `pending` and
  `rejected` states exist in the database schema for later). Moderation is after the fact with `manage.py`.
- Players can't delete their own uploads through the API yet; the operator does it with `manage.py delete`.
- Rate limits live in memory, per process: restarting the backend resets them, and they assume a single
  uvicorn worker (the systemd unit runs one).
- SQLite and local files limit this to one machine and modest traffic; the API is offline whenever that
  machine is.
- **Automatic bans can be abused by groups.** Six real Minecraft accounts reporting the same player ban them for
  a week. Reporters must be signed in, count once each and are limited per day, the ban only affects Orbit
  Cloud (featured cosmetics still work), and Discord shows every case, but a moderator still has to undo unfair
  bans with `manage.py unblock`.
- Reports can't prove the reporter was on the same server as the player.
- The database records its schema version (`PRAGMA user_version`, currently 4); older databases are upgraded
  automatically on start.
- In development mode, presence can be spoofed (no accounts). On offline-mode Minecraft servers, UUIDs come
  from player names, which is why the mod also looks players up by name.

## Still to do for a larger public service

1. **Full validation** before a cosmetic becomes downloadable: reuse the Orbit mod's parser and limits
   (texture size, faces, groups, animations), and add a review or moderation step for public content.
2. **Ownership actions**: let players list and delete their own uploads, and moderators review them.
3. **Storage and database** that can grow: object storage (S3-compatible) for files and PostgreSQL for
   metadata once traffic or availability needs outgrow one machine. `services/cosmetic_storage.py` and
   `models.py` are the only places that need to change.
4. **Monitoring**: an uptime check on `https://api.orbitcosmetics.qd.je/v1/health` and alerts on errors.

Already done for the public deployment: Mojang-verified sign-in tied to presence and uploads, no HTTP delete in
production, HTTPS through Cloudflare Tunnel with the app on `127.0.0.1`, rate limits, upload quota, storage
cap, CORS for the website (read-only), systemd service, daily backups, bans with `manage.py`, the featured
catalog, and player reports with Discord alerts and automatic bans.

## Designed for what comes next

- Endpoints are versioned under `/v1`, so breaking changes can go to `/v2` while old clients keep working.
- Routes only handle HTTP. File handling is in `services/cosmetic_storage.py` and queries in `models.py`, so
  storage and database can be replaced without touching the routes.
- `validation_status` already models a review workflow, and downloads are refused for anything not
  `approved`.
- The SHA-256 identity matches the Orbit mod's asset IDs, which suits multiplayer synchronization through the
  server mod and future client-side browsing.
- A derived runtime format (for example a pre-validated, optimized version of each model) can be stored next
  to the original under the same ID.

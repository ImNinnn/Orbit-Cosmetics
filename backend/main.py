"""Orbit Cosmetics backend.

Run with:  python main.py      (listens on http://127.0.0.1:8000 only)
Docs at:   http://127.0.0.1:8000/docs
Public deployment (production mode behind Cloudflare Tunnel): see deploy/DEPLOYMENT.md
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware

import auth
import config
import database
import models
from errors import error_response, install_handlers
from routes import auth as auth_routes, cosmetics, featured, health, presence, reports
from services.cosmetic_storage import CosmeticStorage
from services.discord import DiscordNotifier

logger = logging.getLogger("orbit")

# Room for the multipart boundaries and form fields around the uploaded file itself.
MULTIPART_OVERHEAD = 64 * 1024


def create_app(settings: config.Settings | None = None) -> FastAPI:
    settings = settings or config.Settings.from_env()
    storage = CosmeticStorage(settings.storage_dir)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        storage.ensure_ready()
        database.init_database(settings.database_path)
        connection = database.connect(settings.database_path)
        try:
            removed = storage.remove_orphans(models.stored_filenames(connection))
        finally:
            connection.close()
        logger.info(
            "Ready: database %s, storage %s%s",
            settings.database_path,
            settings.storage_dir,
            f", removed {removed} orphaned file(s)" if removed else "",
        )
        if settings.dev_mode:
            logger.warning("Development mode: writes are accepted from this computer without accounts. Never expose this mode publicly.")
        else:
            logger.info("Mode: %s", settings.mode)
        bot_task = None
        if settings.run_discord_bot:
            if settings.discord_bot_token:
                # Imported here so the API does not need discord.py unless the bot runs inside it.
                import discord_bot

                bot_task = asyncio.create_task(discord_bot.run_bot(settings))
            else:
                logger.error("ORBIT_DISCORD_BOT is on but there is no bot token (TOKEN in backend/.env)")
        yield
        if bot_task is not None:
            bot_task.cancel()
            try:
                await bot_task
            except asyncio.CancelledError:
                pass

    app = FastAPI(
        title=config.APP_NAME,
        version=config.APP_VERSION,
        description=(
            "Orbit Cloud: cosmetics, presence, the featured catalog and player reports for the Orbit Cosmetic mod. "
            "In development mode, writes are accepted from this computer only; in production they need a player "
            "session verified with Mojang."
        ),
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.storage = storage
    app.state.rate_limiter = auth.RateLimiter()
    # A local test receiver is only accepted in development mode.
    app.state.notifier = DiscordNotifier(settings.discord_webhook_url, allow_local=settings.dev_mode, prefix=settings.discord_bot_prefix)
    install_handlers(app)

    if settings.cors_origins:
        # Lets the Orbit website read the catalogue from the browser. Writes come from the game, not websites.
        app.add_middleware(CORSMiddleware, allow_origins=list(settings.cors_origins), allow_methods=["GET"], allow_headers=["*"], max_age=3600)

    @app.middleware("http")
    async def rate_limit(request: Request, call_next):
        ip = auth.client_ip(request)
        limiter: auth.RateLimiter = request.app.state.rate_limiter
        if request.url.path.startswith(f"{config.API_PREFIX}/auth/") and not limiter.allow(f"auth:{ip}", settings.auth_requests_per_minute):
            return error_response(429, "too_many_requests", "Too many sign-in attempts; wait a minute.", headers={"Retry-After": "60"})
        if not limiter.allow(f"all:{ip}", settings.requests_per_minute):
            return error_response(429, "too_many_requests", "Too many requests; wait a minute.", headers={"Retry-After": "60"})
        return await call_next(request)

    @app.middleware("http")
    async def limit_request_size(request: Request, call_next):
        # Refuse oversized uploads before the body is read. The upload route checks the real size again,
        # which also covers requests without a Content-Length header.
        length = request.headers.get("content-length")
        if length is not None:
            try:
                too_large = int(length) > settings.max_upload_bytes + MULTIPART_OVERHEAD
            except ValueError:
                return error_response(400, "invalid_request", "Invalid Content-Length header.")
            if too_large:
                return error_response(413, "too_large", f"The request is larger than the {settings.max_upload_bytes // 1024} KiB upload limit.")
        return await call_next(request)

    app.include_router(health.router)
    app.include_router(auth_routes.router)
    app.include_router(cosmetics.router)
    app.include_router(presence.router)
    app.include_router(featured.router)
    app.include_router(reports.router)
    return app


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
app = create_app()

if __name__ == "__main__":
    # Loopback only: the prototype must not be reachable from other computers.
    # proxy_headers=False: auth.client_ip decides which proxy headers to believe.
    uvicorn.run("main:app", host=config.HOST, port=config.PORT, proxy_headers=False)

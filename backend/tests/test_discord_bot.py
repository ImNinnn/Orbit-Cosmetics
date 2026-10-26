"""The Discord moderation bot, without connecting to Discord: access checks, server lock and commands."""

from __future__ import annotations

import asyncio
import sqlite3
import time
from types import SimpleNamespace

import pytest

import config
import database
import discord_bot
import models
from conftest import make_settings
from services import moderation

GUILD = 1514172105787183114
OWNER = 111  # owns the Discord server and the bot
OTHER = 222
TOM = "11111111-1111-4111-8111-111111111111"


class FakeContext:
    def __init__(self, author_id=OWNER, guild_id=GUILD, guild_owner=OWNER, dm=False):
        self.author = SimpleNamespace(id=author_id, __str__=lambda: f"user{author_id}")
        self.guild = None if dm else SimpleNamespace(id=guild_id, owner_id=guild_owner)
        self.message = SimpleNamespace(content="o!test")
        self.prefix = "o!"
        self.command = SimpleNamespace(qualified_name="ban", signature="<player> <duration> [reason]")
        self.replies: list = []

    async def reply(self, content=None, *, embed=None):
        self.replies.append(embed if embed is not None else content)


@pytest.fixture
def bot(tmp_path):
    settings = make_settings(tmp_path, discord_bot_token="unused", discord_guild_id=GUILD)
    database.init_database(settings.database_path)
    instance = discord_bot.OrbitBot(settings)
    instance.bot_owner_id = OWNER
    return instance


def run(coroutine):
    return asyncio.run(coroutine)


def text(reply) -> str:
    if isinstance(reply, str):
        return reply
    return " ".join([reply.title or "", reply.description or ""] + [f"{f.name} {f.value}" for f in reply.fields])


def test_both_owner_checks_must_pass():
    assert discord_bot.authorized(GUILD, GUILD, OWNER, OWNER, OWNER)
    assert not discord_bot.authorized(GUILD, GUILD, OWNER, OWNER, OTHER), "server owner but not bot owner"
    assert not discord_bot.authorized(GUILD, GUILD, OWNER, OTHER, OWNER), "bot owner but not server owner"
    assert not discord_bot.authorized(GUILD, GUILD, OTHER, OWNER, OWNER), "neither"
    assert not discord_bot.authorized(999, GUILD, OWNER, OWNER, OWNER), "another server"
    assert not discord_bot.authorized(None, GUILD, OWNER, OWNER, OWNER), "direct message"
    assert not discord_bot.authorized(GUILD, GUILD, OWNER, OWNER, None), "bot owner unknown"


def test_global_check_guards_every_command(bot):
    assert run(bot.can_run(FakeContext())) is True
    with pytest.raises(discord_bot.NotAllowed):
        run(bot.can_run(FakeContext(author_id=OTHER, guild_owner=OTHER)))  # server owner, not bot owner
    bot.bot_owner_id = OTHER
    with pytest.raises(discord_bot.NotAllowed):
        run(bot.can_run(FakeContext()))  # server owner, but the bot belongs to someone else
    bot.bot_owner_id = OWNER
    with pytest.raises(discord_bot.WrongPlace):
        run(bot.can_run(FakeContext(guild_id=999)))
    with pytest.raises(discord_bot.WrongPlace):
        run(bot.can_run(FakeContext(dm=True)))


def test_refusals(bot):
    refused = FakeContext(author_id=OTHER)
    run(bot.on_command_error(refused, discord_bot.NotAllowed()))
    assert "only for the owner" in refused.replies[0]
    elsewhere = FakeContext(guild_id=999)
    run(bot.on_command_error(elsewhere, discord_bot.WrongPlace()))
    assert elsewhere.replies == [], "silent outside the Orbit server"


def test_leaves_other_servers(bot):
    left = []

    async def leave(guild_id):
        left.append(guild_id)

    for guild_id in (GUILD, 999):
        run(bot.on_guild_join(SimpleNamespace(id=guild_id, name=f"g{guild_id}", leave=lambda g=guild_id: leave(g))))
    assert left == [999]


def test_durations():
    assert moderation.parse_duration("7") == 7 * 86400
    assert moderation.parse_duration("7d") == 7 * 86400
    assert moderation.parse_duration("12h") == 12 * 3600
    assert moderation.parse_duration("2w") == 14 * 86400
    assert moderation.parse_duration("perm") is None
    for bad in ("0d", "soon", "-3", "99999w"):
        with pytest.raises(moderation.ModerationError):
            moderation.parse_duration(bad)


def test_ban_unban_and_reports_commands(bot, monkeypatch):
    settings = bot.settings
    monkeypatch.setattr(moderation, "mojang_lookup", lambda name, timeout=5.0: None)
    with sqlite3.connect(settings.database_path) as connection:
        connection.execute("INSERT INTO presence VALUES (?, 'Tom', 2, 'AgA=', ?)", (TOM, time.time()))
        for i in range(2):
            connection.execute(
                "INSERT INTO reports (player_uuid, player_name, reporter_uuid, reason, note, created_at) VALUES (?, 'Tom', ?, 'lag', 'so laggy', ?)",
                (TOM, f"r{i}", time.time()),
            )
    cog = discord_bot.Moderation(bot)

    ctx = FakeContext()
    run(cog.ban.callback(cog, ctx, "tom", "3d", reason="offensive hat <@&123>"))
    assert "Banned Tom" in text(ctx.replies[0])
    with sqlite3.connect(settings.database_path) as connection:
        ban = models.get_ban(connection, TOM, time.time())
        assert ban.source == "discord" and abs(ban.expires_at - time.time() - 3 * 86400) < 60
        assert "offensive hat" in ban.reason
        assert models.get_presence(connection, TOM) is None, "cosmetics hidden at once"

    ctx = FakeContext()
    run(cog.bans.callback(cog, ctx))
    assert "Tom" in text(ctx.replies[0]) and TOM in text(ctx.replies[0])

    ctx = FakeContext()
    run(cog.player_info.callback(cog, ctx, TOM))
    assert "2 reporter(s): lag ×2" in text(ctx.replies[0])

    ctx = FakeContext()
    run(cog.reports.callback(cog, ctx, None))
    assert "2 reporter(s)" in text(ctx.replies[0])
    ctx = FakeContext()
    run(cog.reports.callback(cog, ctx, "Tom"))
    assert "so laggy" in text(ctx.replies[0])

    ctx = FakeContext()
    run(cog.unban.callback(cog, ctx, "Tom"))
    assert "Unbanned" in ctx.replies[0]
    ctx = FakeContext()
    run(cog.unban.callback(cog, ctx, "Tom"))
    assert "not banned" in ctx.replies[0]

    ctx = FakeContext()
    run(cog.clearreports.callback(cog, ctx, TOM))
    assert "Deleted 2" in ctx.replies[0]

    with pytest.raises(moderation.ModerationError):
        run(cog.ban.callback(cog, FakeContext(), "NobodyKnown", "7d"))


def test_unknown_names_are_looked_up_with_mojang(bot, monkeypatch):
    monkeypatch.setattr(moderation, "mojang_lookup", lambda name, timeout=5.0: moderation.Player(TOM, "Tom"))
    cog = discord_bot.Moderation(bot)
    ctx = FakeContext()
    run(cog.ban.callback(cog, ctx, "Tom", "perm"))
    assert "never (permanent)" in text(ctx.replies[0])


def test_token_comes_from_dotenv_and_stays_out_of_logs(tmp_path, monkeypatch):
    (tmp_path / ".env").write_text("# secrets\nTOKEN=abc.def.ghi\nORBIT_DISCORD_BOT_PREFIX='!o '\n")
    monkeypatch.setattr(config, "BASE_DIR", tmp_path)
    for name in ("ORBIT_DISCORD_BOT_TOKEN", "TOKEN", "ORBIT_DISCORD_BOT_PREFIX"):
        monkeypatch.delenv(name, raising=False)
    settings = config.Settings.from_env()
    assert settings.discord_bot_token == "abc.def.ghi"
    assert settings.discord_bot_prefix == "!o"
    assert settings.discord_guild_id == GUILD
    assert "abc.def.ghi" not in repr(settings)
    monkeypatch.setenv("ORBIT_DISCORD_BOT_TOKEN", "from-env")
    assert config.Settings.from_env().discord_bot_token == "from-env"

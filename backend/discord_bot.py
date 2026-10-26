"""Orbit Cloud moderation bot for Discord, with prefix commands (default prefix ``o!``).

Run with:  python discord_bot.py            (or set ORBIT_DISCORD_BOT=1 to run it inside the API process)

The token is read from backend/.env (``TOKEN=...``) or ORBIT_DISCORD_BOT_TOKEN and is never logged.

Commands only work in one Discord server (``ORBIT_DISCORD_GUILD_ID``), and only for someone who passes both
checks: they own that Discord server, and they own the bot application (for a team-owned application, the
team owner). Anywhere else the bot stays silent, and it leaves any other server it is added to.

The bot needs the "Message Content Intent" (Discord Developer Portal > Bot) to read prefix commands.
"""

from __future__ import annotations

import asyncio
import logging
import sys
from datetime import datetime, timezone

import discord
from discord.ext import commands

import config
import database
from services import moderation
from services.moderation import ModerationError, Player

logger = logging.getLogger("orbit.bot")

ORBIT_RED = 0xE0282C
MAX_ROWS = 20


def authorized(guild_id: int | None, allowed_guild_id: int, author_id: int, guild_owner_id: int | None, bot_owner_id: int | None) -> bool:
    """Both checks must pass: the author owns the Discord server, and the author owns the bot."""
    if guild_id is None or guild_id != allowed_guild_id or guild_owner_id is None or bot_owner_id is None:
        return False
    return author_id == guild_owner_id and author_id == bot_owner_id


class WrongPlace(commands.CheckFailure):
    """A DM or another server: ignored without a reply."""


class NotAllowed(commands.CheckFailure):
    """The right server, but not someone who owns both the server and the bot."""


def _name(player: Player) -> str:
    return discord.utils.escape_markdown(player.name) if player.name else "(name unknown)"


def _when(timestamp: float | None, style: str = "f") -> str:
    if timestamp is None:
        return "never (permanent)"
    return discord.utils.format_dt(datetime.fromtimestamp(timestamp, timezone.utc), style)


class OrbitBot(commands.Bot):
    def __init__(self, settings: config.Settings) -> None:
        intents = discord.Intents.default()
        intents.message_content = True
        super().__init__(
            command_prefix=settings.discord_bot_prefix,
            intents=intents,
            help_command=None,
            case_insensitive=True,
            # Never ping anyone, whatever a player name or reason contains.
            allowed_mentions=discord.AllowedMentions.none(),
        )
        self.settings = settings
        self.bot_owner_id: int | None = None
        self.add_check(self.check_access)

    async def setup_hook(self) -> None:
        application = await self.application_info()
        self.bot_owner_id = application.team.owner_id if application.team else application.owner.id
        await self.add_cog(Moderation(self))

    async def on_ready(self) -> None:
        logger.info("Discord bot ready as %s (commands only in server %s)", self.user, self.settings.discord_guild_id)
        for guild in self.guilds:
            await self._leave_if_foreign(guild)

    async def on_guild_join(self, guild: discord.Guild) -> None:
        await self._leave_if_foreign(guild)

    async def _leave_if_foreign(self, guild: discord.Guild) -> None:
        if guild.id != self.settings.discord_guild_id:
            logger.warning("Leaving Discord server %s (%s): the bot only works in %s", guild.name, guild.id, self.settings.discord_guild_id)
            await guild.leave()

    async def check_access(self, ctx: commands.Context) -> bool:
        if ctx.guild is None or ctx.guild.id != self.settings.discord_guild_id:
            raise WrongPlace()
        if self.bot_owner_id is None:
            application = await self.application_info()
            self.bot_owner_id = application.team.owner_id if application.team else application.owner.id
        if not authorized(ctx.guild.id, self.settings.discord_guild_id, ctx.author.id, ctx.guild.owner_id, self.bot_owner_id):
            raise NotAllowed()
        return True

    async def on_command_error(self, ctx: commands.Context, error: commands.CommandError) -> None:
        if isinstance(error, (WrongPlace, commands.CommandNotFound)):
            return
        if isinstance(error, NotAllowed):
            logger.warning("Refused %r from %s (%s)", ctx.message.content[:100], ctx.author, ctx.author.id)
            await ctx.reply("Orbit commands are only for the owner of this server who also owns the bot.")
            return
        if isinstance(error, (commands.MissingRequiredArgument, commands.BadArgument, commands.TooManyArguments)):
            await ctx.reply(f"Usage: `{ctx.prefix}{ctx.command.qualified_name} {ctx.command.signature}`. See `{ctx.prefix}help`.")
            return
        original = getattr(error, "original", error)
        if isinstance(original, ModerationError):
            await ctx.reply(str(original))
            return
        logger.error("Command %r failed", ctx.message.content[:100], exc_info=original)
        await ctx.reply("Something went wrong; see the backend log.")


class Moderation(commands.Cog):
    """Bans and reports on Orbit Cloud."""

    def __init__(self, bot: OrbitBot) -> None:
        self.bot = bot

    @property
    def settings(self) -> config.Settings:
        return self.bot.settings

    async def db(self, action, *args):
        """Runs a database action in a worker thread, so the bot (and the API, if it shares the loop) never blocks."""

        def run():
            connection = database.connect(self.settings.database_path)
            try:
                return action(connection, *args)
            finally:
                connection.close()

        return await asyncio.to_thread(run)

    async def player(self, text: str) -> Player:
        found = await self.db(moderation.find_local, text)
        if found is None:
            found = await asyncio.to_thread(moderation.mojang_lookup, text)
        if found is None:
            raise ModerationError(f"No Minecraft player `{discord.utils.escape_markdown(text[:40])}` found. Use a name or a UUID.")
        return found

    def embed(self, title: str, description: str = "") -> discord.Embed:
        return discord.Embed(title=title, description=description[:4000], color=ORBIT_RED)

    @commands.command(name="help")
    async def help_command(self, ctx: commands.Context) -> None:
        p = ctx.prefix
        await ctx.reply(embed=self.embed("Orbit Cloud moderation", "\n".join([
            f"`{p}bans` — players banned right now",
            f"`{p}ban <player> <duration> [reason]` — ban; duration like `7d`, `12h`, `2w` or `perm`",
            f"`{p}unban <player>` — lift a ban (manual or automatic)",
            f"`{p}player <player>` — ban, reports and current cosmetics",
            f"`{p}reports [player]` — reported players, or the reports on one player",
            f"`{p}clearreports <player>` — delete the reports on a player",
            "",
            "`<player>` is a Minecraft name or UUID. Banned players cannot upload; only their featured cosmetics are shared.",
        ])))

    @commands.command()
    async def bans(self, ctx: commands.Context) -> None:
        rows = await self.db(moderation.bans)
        if not rows:
            await ctx.reply("Nobody is banned.")
            return
        lines = [
            f"**{_name(Player(ban.player_uuid, name))}** `{ban.player_uuid}` — until {_when(ban.expires_at)} [{ban.source}]\n"
            f"  {discord.utils.escape_markdown(ban.reason or '-')}"
            for ban, name in rows[:MAX_ROWS]
        ]
        more = f"\n…and {len(rows) - MAX_ROWS} more" if len(rows) > MAX_ROWS else ""
        await ctx.reply(embed=self.embed(f"Banned players ({len(rows)})", "\n".join(lines) + more))

    @commands.command()
    async def ban(self, ctx: commands.Context, player: str, duration: str, *, reason: str = "") -> None:
        seconds = moderation.parse_duration(duration)
        target = await self.player(player)
        record = await self.db(moderation.ban, target, seconds, reason, f"{ctx.author} on Discord")
        logger.warning("%s banned %s (%s) until %s", ctx.author, target.name, target.uuid, record.expires_at)
        await ctx.reply(embed=self.embed(
            f"Banned {_name(target)}",
            f"UUID `{target.uuid}`\nUntil {_when(record.expires_at)}\nReason: {discord.utils.escape_markdown(record.reason)}\n\n"
            "Their cosmetics are hidden now; only featured cosmetics will be shared until the ban ends.",
        ))

    @commands.command()
    async def unban(self, ctx: commands.Context, player: str) -> None:
        target = await self.player(player)
        if await self.db(moderation.unban, target):
            logger.warning("%s unbanned %s (%s)", ctx.author, target.name, target.uuid)
            await ctx.reply(f"Unbanned **{_name(target)}** (`{target.uuid}`).")
        else:
            await ctx.reply(f"**{_name(target)}** is not banned.")

    @commands.command(name="player")
    async def player_info(self, ctx: commands.Context, player: str) -> None:
        target = await self.player(player)
        details = await self.db(moderation.info, target, self.settings.report_retention_days)
        embed = self.embed(_name(target), f"UUID `{target.uuid}`")
        ban = details.ban
        embed.add_field(name="Ban", value=f"Until {_when(ban.expires_at)} [{ban.source}]\n{discord.utils.escape_markdown(ban.reason)}"[:1000] if ban else "Not banned", inline=False)
        reasons = ", ".join(f"{reason} ×{count}" for reason, count in details.reasons.items()) or "-"
        embed.add_field(name=f"Reports ({self.settings.report_retention_days} days)", value=f"{details.reporters} reporter(s): {reasons}", inline=False)
        worn = "\n".join(f"• {discord.utils.escape_markdown(name)} `{sha[:16]}`" for sha, name in details.cosmetics) or "Not online through Orbit Cloud"
        embed.add_field(name="Current cosmetics", value=worn[:1000], inline=False)
        await ctx.reply(embed=embed)

    @commands.command()
    async def reports(self, ctx: commands.Context, player: str | None = None) -> None:
        days = self.settings.report_retention_days
        if player is None:
            rows = await self.db(moderation.reported, days)
            if not rows:
                await ctx.reply(f"No reports in the last {days} days.")
                return
            lines = [
                f"**{discord.utils.escape_markdown(name)}** `{uuid}` — {reporters} reporter(s), latest {_when(latest, 'R')}"
                for uuid, name, reporters, latest in rows[:MAX_ROWS]
            ]
            await ctx.reply(embed=self.embed(f"Reported players ({len(rows)})", "\n".join(lines)))
            return
        target = await self.player(player)
        entries = await self.db(moderation.reports_on, target, days)
        if not entries:
            await ctx.reply(f"No reports on **{_name(target)}** in the last {days} days.")
            return
        lines = [
            f"{_when(report.created_at, 'R')} — **{report.reason}**" + (f": {discord.utils.escape_markdown(report.note)}" if report.note else "")
            for report in entries[-MAX_ROWS:]
        ]
        await ctx.reply(embed=self.embed(f"Reports on {_name(target)} ({len(entries)})", "\n".join(lines)))

    @commands.command()
    async def clearreports(self, ctx: commands.Context, player: str) -> None:
        target = await self.player(player)
        removed = await self.db(moderation.clear_reports, target)
        logger.warning("%s cleared %d report(s) on %s (%s)", ctx.author, removed, target.name, target.uuid)
        await ctx.reply(f"Deleted {removed} report(s) on **{_name(target)}**.")


async def run_bot(settings: config.Settings) -> None:
    """Runs the bot until cancelled. Used by the API when ORBIT_DISCORD_BOT=1; failures are logged, not raised."""
    bot = OrbitBot(settings)
    try:
        await bot.start(settings.discord_bot_token)
    except discord.LoginFailure:
        logger.error("Discord rejected the bot token (TOKEN in backend/.env); the bot is not running")
    except discord.PrivilegedIntentsRequired:
        logger.error("Enable the Message Content Intent for the bot in the Discord Developer Portal; the bot is not running")
    except asyncio.CancelledError:
        raise
    except Exception:
        logger.exception("The Discord bot stopped")
    finally:
        if not bot.is_closed():
            await bot.close()


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    settings = config.Settings.from_env()
    if not settings.discord_bot_token:
        print("No bot token: put TOKEN=<token> in backend/.env (or set ORBIT_DISCORD_BOT_TOKEN).", file=sys.stderr)
        return 1
    database.init_database(settings.database_path)
    try:
        asyncio.run(run_bot(settings))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())

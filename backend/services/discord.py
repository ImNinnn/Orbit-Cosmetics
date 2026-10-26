"""Discord webhook messages for moderators.

The webhook URL is a secret (anyone who has it can post to the channel), so it only comes from the
ORBIT_DISCORD_WEBHOOK_URL environment variable. Messages never mention anyone and escape everything that
players can influence (names, notes, cosmetic names).
"""

from __future__ import annotations

import json
import logging
import threading
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

logger = logging.getLogger("orbit.discord")

DISCORD_HOSTS = frozenset({"discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com"})
LOCAL_HOSTS = frozenset({"127.0.0.1", "localhost", "::1"})
TIMEOUT_SECONDS = 10


def valid_webhook_url(url: str, allow_local: bool = False) -> bool:
    """Discord webhook URLs only (plus a local test receiver in development mode)."""
    try:
        parsed = urllib.parse.urlsplit(url)
    except ValueError:
        return False
    host = (parsed.hostname or "").lower()
    if allow_local and host in LOCAL_HOSTS and parsed.scheme in ("http", "https"):
        return True
    return parsed.scheme == "https" and host in DISCORD_HOSTS and parsed.path.startswith("/api/webhooks/")


def escape(text: str, limit: int = 200) -> str:
    """Makes player-controlled text inert in Discord markdown and keeps it short."""
    cleaned = " ".join(str(text).split())[:limit]
    for character in "\\*_~`|>#[]()<@:":
        cleaned = cleaned.replace(character, "\\" + character)
    return cleaned or "-"


@dataclass(frozen=True)
class ReportedCosmetic:
    sha256: str
    name: str
    cosmetic_id: str | None = None


@dataclass(frozen=True)
class ReportAlert:
    player_uuid: str
    player_name: str
    reporters: int
    reasons: dict[str, int]
    notes: list[str] = field(default_factory=list)
    cosmetics: list[ReportedCosmetic] = field(default_factory=list)
    banned_days: int | None = None


def build_message(alert: ReportAlert, prefix: str = "o!") -> dict:
    banned = alert.banned_days is not None
    title = (
        f"{escape(alert.player_name, 16)} was banned from Orbit Cloud for {alert.banned_days} days"
        if banned
        else f"{escape(alert.player_name, 16)} was reported by {alert.reporters} players"
    )
    reasons = ", ".join(f"{escape(reason, 32)} ×{count}" for reason, count in sorted(alert.reasons.items(), key=lambda item: -item[1]))
    if alert.cosmetics:
        cosmetics = "\n".join(
            f"• {escape(c.name, 64)} — `{c.sha256[:16]}`" + (f" (id `{c.cosmetic_id}`)" if c.cosmetic_id else " (not on Orbit Cloud)")
            for c in alert.cosmetics[:16]
        )
    else:
        cosmetics = "None known"
    fields = [
        {"name": "Player", "value": escape(alert.player_name, 16), "inline": True},
        {"name": "UUID", "value": f"`{alert.player_uuid}`", "inline": True},
        {"name": "Reports (last 7 days)", "value": str(alert.reporters), "inline": True},
        {"name": "Reasons", "value": reasons or "-", "inline": False},
        {"name": "Current cosmetics", "value": cosmetics[:1000], "inline": False},
    ]
    if alert.notes:
        fields.append({"name": "Notes from reporters", "value": "\n".join(f"• {escape(note, 120)}" for note in alert.notes[:5])[:1000], "inline": False})
    footer = (
        f"Automatic ban after repeated reports. Undo with: {prefix}unban {alert.player_uuid}"
        if banned
        else f"Ban with: {prefix}ban {alert.player_uuid} 7d <reason>  •  details: {prefix}player {alert.player_uuid}"
    )
    return {
        "username": "Orbit Cloud",
        # Never ping anyone, whatever the text contains.
        "allowed_mentions": {"parse": []},
        "embeds": [
            {
                "title": title,
                "color": 0xE5484D if banned else 0xF5A524,
                "fields": fields,
                "footer": {"text": footer},
            }
        ],
    }


class DiscordNotifier:
    """Posts alerts in a background thread so a slow Discord never delays an API request."""

    def __init__(self, url: str, allow_local: bool = False, prefix: str = "o!") -> None:
        self.url = url if url and valid_webhook_url(url, allow_local) else ""
        self.prefix = prefix
        if url and not self.url:
            logger.error("ORBIT_DISCORD_WEBHOOK_URL is not a Discord webhook URL; report alerts are disabled")

    @property
    def enabled(self) -> bool:
        return bool(self.url)

    def send(self, alert: ReportAlert) -> None:
        if not self.enabled:
            logger.warning("Report alert for %s (%s), but no Discord webhook is configured", alert.player_name, alert.player_uuid)
            return
        threading.Thread(target=self._post, args=(build_message(alert, self.prefix),), name="orbit-discord", daemon=True).start()

    def _post(self, message: dict) -> None:
        request = urllib.request.Request(
            self.url,
            data=json.dumps(message).encode("utf-8"),
            headers={"Content-Type": "application/json", "User-Agent": "OrbitCosmeticsBackend"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                response.read(1024)
        except urllib.error.HTTPError as error:
            logger.error("Discord webhook answered HTTP %s", error.code)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            logger.error("Could not reach the Discord webhook: %s", error)

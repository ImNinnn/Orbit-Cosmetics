package dev.orbit.cosmetic.common.server;

import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The {@code /orbit} administration command, shared by both server platforms. Platforms check the admin
 * permission, split the arguments and print the returned lines.
 */
public final class OrbitAdmin {
	public static final List<String> SUBCOMMANDS = List.of(
		"status", "enable", "disable", "block", "unblock", "players", "assets", "approve", "unapprove", "ban", "unban", "set", "log", "purge", "reload", "help"
	);
	public static final List<String> SETTINGS = List.of(
		"uploads", "downloads", "icons", "animations", "requireApproval", "maxEquipped", "maxAssetKilobytes", "maxTextureSize", "maxFaces", "maxAnimations"
	);

	private final OrbitServerCore core;

	OrbitAdmin(OrbitServerCore core) {
		this.core = core;
	}

	public List<String> execute(String[] args) {
		if (args.length == 0) {
			return help();
		}
		ServerConfig config = core.config();
		String sub = args[0].toLowerCase(Locale.ROOT);
		return switch (sub) {
			case "status" -> status();
			case "enable", "disable" -> {
				config.enabled = sub.equals("enable");
				core.applyConfigChange(false);
				yield List.of("Orbit cosmetics are now " + (config.enabled ? "enabled" : "disabled") + " for everyone.");
			}
			case "block", "unblock" -> block(args, sub.equals("block"));
			case "players" -> players();
			case "assets" -> assets();
			case "approve", "unapprove", "ban", "unban" -> assetList(args, sub);
			case "set" -> set(args);
			case "log" -> log(args);
			case "purge" -> purge();
			case "reload" -> {
				core.reload();
				yield List.of("Reloaded orbit-server.json.");
			}
			default -> help();
		};
	}

	public List<String> complete(String[] args) {
		if (args.length <= 1) {
			return filter(SUBCOMMANDS.stream(), args.length == 0 ? "" : args[0]);
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		String last = args[args.length - 1];
		if (args.length == 2) {
			return switch (sub) {
				case "block", "unblock" -> filter(core.sessions().stream().map(OrbitServerCore.Session::displayName), last);
				case "approve", "ban" -> filter(core.store().ids().stream().map(AssetId::shortHex), last);
				case "unapprove" -> filter(core.config().approvedAssets.stream().map(hex -> hex.substring(0, 12)), last);
				case "unban" -> filter(core.config().bannedAssets.stream().map(hex -> hex.substring(0, 12)), last);
				case "set" -> filter(SETTINGS.stream(), last);
				default -> List.of();
			};
		}
		if (args.length == 3 && sub.equals("set")) {
			return filter(Stream.of("true", "false"), last);
		}
		return List.of();
	}

	private List<String> help() {
		return List.of(
			"/orbit status - overview",
			"/orbit enable|disable - turn Orbit on or off for everyone",
			"/orbit block|unblock <player|uuid> - stop or allow a player wearing cosmetics",
			"/orbit players - online Orbit players and what they wear",
			"/orbit assets - stored cosmetics",
			"/orbit approve|unapprove <hash> - manage approved cosmetics",
			"/orbit ban|unban <hash> - forbid a cosmetic everywhere",
			"/orbit set <setting> <value> - " + String.join(", ", SETTINGS),
			"/orbit log [count] - recent errors and security events",
			"/orbit purge - delete stored cosmetics nobody wears that are not approved",
			"/orbit reload - reload orbit-server.json"
		);
	}

	private List<String> status() {
		ServerConfig config = core.config();
		long players = core.sessions().stream().filter(s -> s.compatible).count();
		long wearing = core.sessions().stream().filter(s -> !s.visible.isEmpty()).count();
		return List.of(
			"Orbit Cosmetic: " + (config.enabled ? "enabled" : "disabled"),
			"Orbit players online: " + players + " (" + wearing + " wearing cosmetics)",
			"Uploads " + onOff(config.allowUploads) + ", downloads " + onOff(config.allowDownloads) + ", animations " + onOff(config.allowAnimations)
				+ ", nameplate icons " + onOff(config.showNameplateIcons) + ", approval required " + onOff(config.requireApprovedAssets),
			"Stored cosmetics: " + core.store().count() + " (" + core.store().totalBytes() / 1024 + " KiB of " + config.maxStoreMegabytes + " MiB)",
			"Blocked players: " + config.blockedPlayers.size() + ", approved: " + config.approvedAssets.size() + ", banned: " + config.bannedAssets.size()
		);
	}

	private List<String> block(String[] args, boolean block) {
		if (args.length < 2) {
			return List.of("Usage: /orbit " + (block ? "block" : "unblock") + " <player|uuid>");
		}
		UUID target = resolvePlayer(args[1]);
		if (target == null) {
			return List.of("No online player named " + args[1] + ". Offline players can be given by UUID.");
		}
		ServerConfig config = core.config();
		String key = target.toString();
		if (block) {
			if (!config.blockedPlayers.contains(key)) {
				config.blockedPlayers.add(key);
			}
		} else {
			config.blockedPlayers.remove(key);
		}
		core.applyConfigChange(false);
		return List.of((block ? "Blocked " : "Unblocked ") + core.describe(target) + ".");
	}

	private UUID resolvePlayer(String text) {
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			return core.platform().findOnlinePlayer(text);
		}
	}

	private List<String> players() {
		List<String> lines = new ArrayList<>();
		for (OrbitServerCore.Session session : core.sessions()) {
			if (!session.compatible) {
				lines.add(session.displayName() + ": incompatible Orbit version");
				continue;
			}
			StringBuilder line = new StringBuilder(session.displayName()).append(": ");
			if (!session.policy.canWear()) {
				line.append("blocked");
			} else if (session.requested.isEmpty()) {
				line.append("no cosmetics");
			} else {
				List<String> items = new ArrayList<>();
				for (EquippedCosmetic cosmetic : session.requested) {
					boolean visible = session.visible.stream().anyMatch(v -> v.asset().equals(cosmetic.asset()));
					items.add(cosmetic.name() + " [" + cosmetic.asset().shortHex() + (visible ? "" : ", hidden") + "]");
				}
				line.append(String.join(", ", items));
			}
			lines.add(line.toString());
		}
		return lines.isEmpty() ? List.of("No Orbit players online.") : lines;
	}

	private List<String> assets() {
		ServerConfig config = core.config();
		List<String> lines = new ArrayList<>();
		Map<AssetId, OrbitServerCore.AssetSummary> summaries = core.summaries();
		for (AssetId id : core.store().ids().stream().sorted().toList()) {
			OrbitServerCore.AssetSummary summary = summaries.get(id);
			List<String> tags = new ArrayList<>();
			if (config.approvedAssets.contains(id.hex())) {
				tags.add("approved");
			}
			if (config.bannedAssets.contains(id.hex())) {
				tags.add("banned");
			}
			String rejection = core.rejection(id);
			if (rejection != null) {
				tags.add("rejected: " + rejection);
			}
			List<String> wearers = core.sessions().stream()
				.filter(s -> s.requested.stream().anyMatch(c -> c.asset().equals(id)))
				.map(OrbitServerCore.Session::displayName)
				.toList();
			if (!wearers.isEmpty()) {
				tags.add("worn by " + String.join(", ", wearers));
			}
			String description = summary == null ? "not validated yet" : summary.name() + ", " + summary.faces() + " faces, " + summary.animations() + " animations";
			lines.add(id.shortHex() + " " + description + (tags.isEmpty() ? "" : " (" + String.join("; ", tags) + ")"));
		}
		return lines.isEmpty() ? List.of("No stored cosmetics.") : lines;
	}

	private List<String> assetList(String[] args, String sub) {
		if (args.length < 2) {
			return List.of("Usage: /orbit " + sub + " <hash or hash prefix>");
		}
		ServerConfig config = core.config();
		List<String> list = sub.endsWith("approve") ? config.approvedAssets : config.bannedAssets;
		boolean add = !sub.startsWith("un");
		String hex = resolveHash(args[1], add ? core.store().ids().stream().map(AssetId::hex).toList() : list);
		if (hex == null) {
			return List.of("No single cosmetic matches \"" + args[1] + "\". Use at least 8 characters of the hash shown by /orbit assets.");
		}
		if (add) {
			if (!list.contains(hex)) {
				list.add(hex);
			}
			if (sub.equals("ban")) {
				config.approvedAssets.remove(hex);
			}
		} else {
			list.remove(hex);
		}
		core.applyConfigChange(false);
		if (sub.equals("ban")) {
			core.assetBanned(AssetId.tryParse(hex));
		}
		return List.of(switch (sub) {
			case "approve" -> "Approved " + hex.substring(0, 12) + ".";
			case "unapprove" -> "Removed approval of " + hex.substring(0, 12) + ".";
			case "ban" -> "Banned " + hex.substring(0, 12) + "; nobody can wear it on this server.";
			default -> "Unbanned " + hex.substring(0, 12) + ".";
		});
	}

	private static String resolveHash(String text, List<String> candidates) {
		String prefix = text.trim().toLowerCase(Locale.ROOT);
		if (AssetId.tryParse(prefix) != null) {
			return prefix;
		}
		if (prefix.length() < 8) {
			return null;
		}
		List<String> matches = candidates.stream().filter(hex -> hex.startsWith(prefix)).toList();
		return matches.size() == 1 ? matches.getFirst() : null;
	}

	private List<String> set(String[] args) {
		if (args.length < 3) {
			return List.of("Usage: /orbit set <setting> <value>. Settings: " + String.join(", ", SETTINGS));
		}
		ServerConfig config = core.config();
		String value = args[2];
		boolean revalidate = false;
		try {
			switch (args[1].toLowerCase(Locale.ROOT)) {
				case "uploads" -> config.allowUploads = parseBoolean(value);
				case "downloads" -> config.allowDownloads = parseBoolean(value);
				case "icons" -> config.showNameplateIcons = parseBoolean(value);
				case "animations" -> config.allowAnimations = parseBoolean(value);
				case "requireapproval" -> config.requireApprovedAssets = parseBoolean(value);
				case "maxequipped" -> config.maxEquippedPerPlayer = Integer.parseInt(value);
				case "maxassetkilobytes" -> {
					config.limits.maxAssetKilobytes = Integer.parseInt(value);
					revalidate = true;
				}
				case "maxtexturesize" -> {
					config.limits.maxTextureSize = Integer.parseInt(value);
					revalidate = true;
				}
				case "maxfaces" -> {
					config.limits.maxFaces = Integer.parseInt(value);
					revalidate = true;
				}
				case "maxanimations" -> {
					config.limits.maxAnimations = Integer.parseInt(value);
					revalidate = true;
				}
				default -> {
					return List.of("Unknown setting. Settings: " + String.join(", ", SETTINGS));
				}
			}
		} catch (IllegalArgumentException e) {
			return List.of("Invalid value \"" + value + "\".");
		}
		core.applyConfigChange(revalidate);
		return List.of("Set " + args[1] + " to " + value + ".");
	}

	private List<String> log(String[] args) {
		int count = 10;
		if (args.length >= 2) {
			try {
				count = Math.max(1, Math.min(100, Integer.parseInt(args[1])));
			} catch (NumberFormatException ignored) {
				// keep default
			}
		}
		List<String> lines = core.securityLog().recent(count).stream().map(SecurityLog.Entry::toString).toList();
		return lines.isEmpty() ? List.of("No events recorded since the server started.") : lines;
	}

	private List<String> purge() {
		ServerConfig config = core.config();
		int removed = 0;
		for (AssetId id : core.store().ids()) {
			boolean worn = core.sessions().stream().anyMatch(s -> s.requested.stream().anyMatch(c -> c.asset().equals(id)));
			if (!worn && !config.approvedAssets.contains(id.hex())) {
				core.store().delete(id);
				core.summaries().remove(id);
				removed++;
			}
		}
		return List.of("Deleted " + removed + " unused cosmetic(s).");
	}

	private static boolean parseBoolean(String value) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "true", "on", "yes", "1" -> true;
			case "false", "off", "no", "0" -> false;
			default -> throw new IllegalArgumentException(value);
		};
	}

	private static String onOff(boolean value) {
		return value ? "on" : "off";
	}

	private static List<String> filter(Stream<String> options, String prefix) {
		String lower = prefix.toLowerCase(Locale.ROOT);
		return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
	}

	public static String[] split(String line) {
		return Arrays.stream(line.trim().split("\\s+")).filter(s -> !s.isEmpty()).toArray(String[]::new);
	}
}

package dev.orbit.cosmetic.common.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.protocol.OrbitProtocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Server configuration, stored as {@code orbit-server.json}. Field names are the JSON keys.
 * Unknown or invalid values are replaced with defaults by {@link #normalize()}.
 */
public final class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Master switch. When false, Orbit is invisible to everyone; gameplay is unaffected. */
	public boolean enabled = true;
	/** Allow players to upload their own (unverified) cosmetics. */
	public boolean allowUploads = true;
	/** Allow clients to download cosmetics worn by others. */
	public boolean allowDownloads = true;
	/** Only distribute assets an administrator approved with {@code /orbit approve}. */
	public boolean requireApprovedAssets = false;
	public boolean showNameplateIcons = true;
	public boolean allowAnimations = true;
	public int maxEquippedPerPlayer = 4;
	/** Body parts cosmetics may attach to: HEAD, BODY, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG, ROOT. */
	public List<String> allowedAnchors = new ArrayList<>(List.of("HEAD", "BODY", "LEFT_ARM", "RIGHT_ARM", "LEFT_LEG", "RIGHT_LEG", "ROOT"));
	public Limits limits = new Limits();
	/** Disk space for stored assets; least recently used unapproved assets are evicted beyond it. */
	public long maxStoreMegabytes = 256;
	/** Total asset download bandwidth per tick, shared by all players. */
	public int downloadKilobytesPerTick = 256;
	public int maxUploadKilobytesPerMinute = 4096;
	public int maxMessagesPerSecond = 40;
	public List<String> blockedPlayers = new ArrayList<>();
	public List<String> approvedAssets = new ArrayList<>();
	public List<String> bannedAssets = new ArrayList<>();
	public String disabledMessage = "Orbit cosmetics are disabled on this server.";
	public String blockedMessage = "You are not allowed to use Orbit cosmetics on this server.";

	public static final class Limits {
		public int maxAssetKilobytes = ModelLimits.DEFAULT.maxFileBytes() / 1024;
		public int maxElements = ModelLimits.DEFAULT.maxElements();
		public int maxGroups = ModelLimits.DEFAULT.maxBones();
		public int maxNesting = ModelLimits.DEFAULT.maxDepth();
		public int maxFaces = ModelLimits.DEFAULT.maxQuads();
		public int maxTextures = ModelLimits.DEFAULT.maxTextures();
		public int maxTextureSize = ModelLimits.DEFAULT.maxTextureSize();
		public int maxTextureKilobytes = ModelLimits.DEFAULT.maxTotalTextureBytes() / 1024;
		public int maxAnimations = ModelLimits.DEFAULT.maxAnimations();
		public int maxKeyframes = ModelLimits.DEFAULT.maxKeyframes();
	}

	public static ServerConfig load(Path file, OrbitLogger logger) {
		ServerConfig config = new ServerConfig();
		if (Files.exists(file)) {
			try {
				ServerConfig loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ServerConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | JsonParseException e) {
				logger.error("Could not read " + file + "; using defaults (the broken file is kept as .broken)", e);
				try {
					Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ignored) {
					// best effort
				}
			}
		}
		config.normalize();
		config.save(file, logger);
		return config;
	}

	public void save(Path file, OrbitLogger logger) {
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			logger.error("Could not save " + file, e);
		}
	}

	public void normalize() {
		maxEquippedPerPlayer = clamp(maxEquippedPerPlayer, 0, OrbitProtocol.MAX_EQUIPPED);
		if (limits == null) {
			limits = new Limits();
		}
		maxStoreMegabytes = Math.max(1, Math.min(maxStoreMegabytes, 1_000_000));
		downloadKilobytesPerTick = clamp(downloadKilobytesPerTick, 8, 16384);
		maxUploadKilobytesPerMinute = clamp(maxUploadKilobytesPerMinute, 64, 1_000_000);
		maxMessagesPerSecond = clamp(maxMessagesPerSecond, 5, 1000);
		allowedAnchors = cleanAnchors(allowedAnchors);
		blockedPlayers = cleanList(blockedPlayers, value -> {
			try {
				return UUID.fromString(value).toString();
			} catch (IllegalArgumentException e) {
				return null;
			}
		});
		approvedAssets = cleanList(approvedAssets, ServerConfig::cleanHash);
		bannedAssets = cleanList(bannedAssets, ServerConfig::cleanHash);
		if (disabledMessage == null) {
			disabledMessage = "";
		}
		if (blockedMessage == null) {
			blockedMessage = "";
		}
	}

	public ModelLimits modelLimits() {
		Limits l = limits;
		return new ModelLimits(
			l.maxAssetKilobytes * 1024,
			l.maxElements,
			l.maxGroups,
			l.maxNesting,
			l.maxFaces,
			l.maxTextures,
			l.maxTextureSize,
			l.maxTextureKilobytes * 1024,
			allowAnimations ? l.maxAnimations : ModelLimits.HARD.maxAnimations(),
			l.maxKeyframes,
			ModelLimits.DEFAULT.maxExpressionLength()
		).clampToHard();
	}

	public Set<Anchor> anchorSet() {
		Set<Anchor> set = EnumSet.noneOf(Anchor.class);
		for (String name : allowedAnchors) {
			set.add(Anchor.valueOf(name));
		}
		return set;
	}

	public boolean isBlocked(UUID player) {
		return blockedPlayers.contains(player.toString());
	}

	private static List<String> cleanAnchors(List<String> values) {
		List<String> result = new ArrayList<>();
		if (values == null) {
			return result;
		}
		for (String value : values) {
			if (value == null) {
				continue;
			}
			String upper = value.trim().toUpperCase(Locale.ROOT);
			for (Anchor anchor : Anchor.values()) {
				if (anchor.name().equals(upper) && !result.contains(upper)) {
					result.add(upper);
				}
			}
		}
		return result;
	}

	private static String cleanHash(String value) {
		String lower = value.trim().toLowerCase(Locale.ROOT);
		return dev.orbit.cosmetic.common.util.Hashes.isValidHex(lower) ? lower : null;
	}

	private static List<String> cleanList(List<String> values, java.util.function.UnaryOperator<String> cleaner) {
		Set<String> result = new LinkedHashSet<>();
		if (values != null) {
			for (String value : values) {
				if (value != null) {
					String cleaned = cleaner.apply(value.trim());
					if (cleaned != null) {
						result.add(cleaned);
					}
				}
			}
		}
		return new ArrayList<>(result);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}

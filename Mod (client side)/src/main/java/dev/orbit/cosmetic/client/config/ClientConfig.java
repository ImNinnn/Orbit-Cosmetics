package dev.orbit.cosmetic.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.common.model.ModelLimits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Local preferences, saved as {@code config/orbit_cosmetic.json}. They never override server policy. */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final String DEFAULT_BACKEND_URL = "https://api.orbitcosmetics.qd.je";

	public enum AttachMode {
		/** Follow the parts of the model actually being rendered (vanilla, or EMF/Fresh Animations). */
		ANIMATED_MODEL,
		/** Always follow a private vanilla player model, ignoring model replacement mods. */
		VANILLA
	}

	public boolean renderCosmetics = true;
	public boolean showOtherPlayers = true;
	public boolean showInFirstPerson = true;
	public boolean playAnimations = true;
	public boolean showNameplateIcons = true;
	/** Show your own nameplate in third-person view. */
	public boolean showOwnNameplate = false;
	public boolean downloadFromServers = true;
	/**
	 * On servers without the Orbit server mod or plugin, share cosmetics through the Orbit backend. Servers with
	 * Orbit always take precedence; the backend is never used there.
	 */
	public boolean useBackend = true;
	/** Address of the Orbit backend. */
	public String backendUrl = DEFAULT_BACKEND_URL;
	/** Cosmetics on players further away than this many blocks are not rendered. */
	public int renderDistance = 64;
	/** Animations of players further away than this many blocks are frozen. */
	public int animationDistance = 32;
	/** Performance limit: faces per player before further cosmetics on that player are skipped. */
	public int maxFacesPerPlayer = 8192;
	public int maxCacheMegabytes = 128;
	public AttachMode attachMode = AttachMode.ANIMATED_MODEL;
	/** On-screen notifications (loading cosmetics, connections, bans, uploads). */
	public boolean showNotifications = true;
	/** Players whose cosmetics you hid in the Players menu (UUIDs). */
	public List<String> hiddenPlayers = new ArrayList<>();

	private transient Path file;

	public static ClientConfig load(Path file) {
		ClientConfig config = new ClientConfig();
		if (Files.exists(file)) {
			try {
				ClientConfig loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClientConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | JsonParseException e) {
				OrbitClient.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		config.file = file;
		config.normalize();
		config.save();
		return config;
	}

	public void normalize() {
		renderDistance = clamp(renderDistance, 8, 512);
		animationDistance = clamp(animationDistance, 4, renderDistance);
		maxFacesPerPlayer = clamp(maxFacesPerPlayer, 100, ModelLimits.HARD.maxQuads() * 4);
		maxCacheMegabytes = clamp(maxCacheMegabytes, 8, 4096);
		if (attachMode == null) {
			attachMode = AttachMode.ANIMATED_MODEL;
		}
		if (backendUrl == null || backendUrl.isBlank()) {
			backendUrl = DEFAULT_BACKEND_URL;
		}
		LinkedHashSet<String> hidden = new LinkedHashSet<>();
		if (hiddenPlayers != null) {
			for (String value : hiddenPlayers) {
				try {
					hidden.add(UUID.fromString(value).toString().toLowerCase(Locale.ROOT));
				} catch (IllegalArgumentException | NullPointerException ignored) {
					// dropped
				}
			}
		}
		hiddenPlayers = new ArrayList<>(hidden.stream().limit(2000).toList());
	}

	public boolean isHidden(UUID player) {
		return hiddenPlayers.contains(player.toString());
	}

	public void setHidden(UUID player, boolean hidden) {
		hiddenPlayers.remove(player.toString());
		if (hidden) {
			hiddenPlayers.add(player.toString());
		}
		save();
	}

	public void save() {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not save {}", file, e);
		}
	}

	/** Limits for models this client loads, locally or from servers. */
	public ModelLimits modelLimits() {
		return ModelLimits.DEFAULT;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}

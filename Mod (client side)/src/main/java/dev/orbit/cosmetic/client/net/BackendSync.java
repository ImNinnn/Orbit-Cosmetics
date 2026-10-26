package dev.orbit.cosmetic.client.net;

import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import dev.orbit.cosmetic.common.protocol.OrbitMessage;
import dev.orbit.cosmetic.common.protocol.OrbitProtocol;
import dev.orbit.cosmetic.common.protocol.ProtocolException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Lets Orbit players see each other's cosmetics on servers that do not run the Orbit server mod or plugin.
 *
 * <p>A few seconds after joining a multiplayer server, if no Orbit server identified itself, the client uploads
 * its worn cosmetics to the backend (only those the backend does not have yet), reports what it wears
 * (presence, refreshed every 30 seconds), and every 5 seconds asks the backend what the players in the tab list
 * wear. Downloaded assets are verified against their SHA-256 and cached like assets from Orbit servers. The
 * equipped configuration is stored as an encoded Orbit protocol Equip message, so it is validated by the same
 * decoder as server traffic. As soon as an Orbit server is detected, the backend is no longer used for this.
 *
 * <p>The featured catalog and player reports also go to the backend ("Orbit Cloud"), on any server.
 *
 * <p>Players banned from Orbit Cloud (by moderators, or automatically after many reports) cannot upload, and only
 * their featured cosmetics are shared.
 */
public final class BackendSync {
	private static final int ACTIVATE_AFTER_TICKS = 100;
	private static final int QUERY_EVERY_TICKS = 100;
	private static final int PUBLISH_EVERY_TICKS = 600;
	private static final int PUBLISH_DEBOUNCE_TICKS = 20;
	private static final long DOWNLOAD_RETRY_MS = 60_000;
	/** The development backend refuses larger uploads. */
	private static final int MAX_UPLOAD_BYTES = 1024 * 1024;
	private static final int MAX_QUERY_PLAYERS = 200;

	private final OrbitClient orbit;
	private final ClientSession session;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Orbit backend");
		thread.setDaemon(true);
		return thread;
	});

	private BackendClient client;
	/** True when the backend requires a Mojang-verified sign-in before publishing. */
	private boolean authRequired;
	/** Who we are signed in as (production backends); null when not signed in. */
	private volatile UUID signedInAs;
	private boolean connected;
	private boolean active;
	private boolean checking;
	private int ticksSinceJoin;
	private int publishCountdown = -1;
	private int sinceQuery;
	private int sincePublish;
	private boolean queryInFlight;
	private UUID publishedAs;
	/** Players whose cosmetics came from the backend, so they can be removed again. */
	private final Set<UUID> backendPlayers = new HashSet<>();
	private final Set<AssetId> downloading = new HashSet<>();
	private final Map<AssetId, Long> retryAfter = new HashMap<>();
	/** Assets the backend is known to have (cleared when the URL changes). */
	private final Set<String> uploaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private boolean warned;
	/** Ban state from the last sign-in or refused request. 0 = permanent. */
	private volatile boolean banned;
	private volatile double bannedUntil;
	/** SHA-256 of featured cosmetics, which banned players may still share. */
	private volatile Set<String> featuredHashes = Set.of();
	/** Problems already announced on this connection (asset and kind), so they are not repeated every refresh. */
	private final Set<String> announced = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public BackendSync(OrbitClient orbit, ClientSession session) {
		this.orbit = orbit;
		this.session = session;
	}

	public boolean isActive() {
		return active;
	}

	/** The backend URL in use, for the settings screen. */
	public String url() {
		return client == null ? orbit.config().backendUrl : client.baseUrl();
	}

	// ---------------------------------------------------------------- lifecycle

	public void onJoin() {
		reset();
		connected = true;
		warned = false;
		announced.clear();
	}

	public void onDisconnect() {
		if (active && publishedAs != null) {
			UUID player = publishedAs;
			BackendClient current = client;
			worker.execute(() -> quietly(() -> current.deletePresence(player)));
		}
		reset();
	}

	/** An Orbit server identified itself: it is authoritative, so the backend is not used on this connection. */
	public void onOrbitServer() {
		if (active) {
			OrbitClient.LOGGER.info("Orbit server detected; the backend fallback is off for this server");
			if (publishedAs != null) {
				UUID player = publishedAs;
				BackendClient current = client;
				worker.execute(() -> quietly(() -> current.deletePresence(player)));
			}
		}
		removeBackendPlayers();
		active = false;
		connected = false;
	}

	/** The worn cosmetics or their settings changed. */
	public void markDirty() {
		if (active) {
			publishCountdown = PUBLISH_DEBOUNCE_TICKS;
		}
	}

	private void reset() {
		removeBackendPlayers();
		authRequired = false;
		connected = false;
		active = false;
		checking = false;
		ticksSinceJoin = 0;
		publishCountdown = -1;
		sinceQuery = 0;
		sincePublish = 0;
		queryInFlight = false;
		publishedAs = null;
		downloading.clear();
		retryAfter.clear();
	}

	private void removeBackendPlayers() {
		for (UUID player : backendPlayers) {
			session.removeRemote(player);
		}
		backendPlayers.clear();
	}

	// ---------------------------------------------------------------- ticking

	public void tick(Minecraft minecraft) {
		if (!connected || minecraft.player == null || minecraft.getConnection() == null) {
			return;
		}
		if (session.isOrbitServer()) {
			onOrbitServer();
			return;
		}
		if (!active) {
			if (++ticksSinceJoin >= ACTIVATE_AFTER_TICKS && !checking && eligible(minecraft)) {
				activate();
			}
			return;
		}
		if (publishCountdown >= 0 && publishCountdown-- == 0 || ++sincePublish >= PUBLISH_EVERY_TICKS) {
			publishCountdown = -1;
			sincePublish = 0;
			publish(minecraft);
		}
		if (++sinceQuery >= QUERY_EVERY_TICKS && !queryInFlight) {
			sinceQuery = 0;
			query(minecraft);
		}
	}

	/** Not in plain singleplayer (nobody else can join), and only if enabled. */
	private boolean eligible(Minecraft minecraft) {
		if (!orbit.config().useBackend) {
			return false;
		}
		return minecraft.getSingleplayerServer() == null || minecraft.getSingleplayerServer().isPublished();
	}

	/** The client for the configured URL, created when needed. Game thread only. */
	private BackendClient ensureClient() {
		try {
			String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(OrbitClient.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("dev");
			BackendClient candidate = client != null && client.baseUrl().equals(orbit.config().backendUrl.trim().replaceAll("/+$", "")) ? client : new BackendClient(orbit.config().backendUrl, version);
			if (candidate != client) {
				uploaded.clear();
				signedInAs = null;
			}
			client = candidate;
			return candidate;
		} catch (IllegalArgumentException e) {
			warnOnce("Orbit backend disabled: " + e.getMessage());
			return null;
		}
	}

	private void activate() {
		BackendClient candidate = ensureClient();
		if (candidate == null) {
			connected = false;
			return;
		}
		checking = true;
		Minecraft minecraft = Minecraft.getInstance();
		net.minecraft.client.User user = minecraft.getUser();
		worker.execute(() -> {
			String auth;
			try {
				auth = candidate.health();
			} catch (IOException | InterruptedException e) {
				auth = null;
			}
			boolean signedIn = false;
			if ("mojang".equals(auth)) {
				if (candidate.signedIn() && signedInAs != null) {
					refreshBan(candidate);
					signedIn = candidate.signedIn();
				}
				if (!signedIn) {
					signedIn = signIn(minecraft, user, candidate);
				}
			}
			String result = auth;
			boolean ready = signedIn;
			Minecraft.getInstance().execute(() -> {
				checking = false;
				if (!connected || session.isOrbitServer()) {
					return;
				}
				if (result == null) {
					warnOnce("Orbit backend at " + candidate.baseUrl() + " is not reachable; cosmetics of other players are not shown on this server");
					connected = false;
					return;
				}
				authRequired = "mojang".equals(result);
				active = true;
				OrbitClient.LOGGER.info("No Orbit server here; sharing cosmetics through the backend at {}", candidate.baseUrl());
				Component connectedTitle = Component.translatable("orbit_cosmetic.notify.connected_cloud");
				if (authRequired && !ready) {
					warnOnce("Could not sign in to the Orbit backend with this Minecraft account; you can see other players' cosmetics but yours are not shared");
					Notifications.warning(connectedTitle, Component.translatable("orbit_cosmetic.notify.connected_cloud.view_only"));
				} else if (authRequired && banned) {
					Notifications.warning(connectedTitle, Component.translatable("orbit_cosmetic.notify.connected_cloud.banned"));
				} else {
					Notifications.success(connectedTitle, authRequired
						? Component.translatable("orbit_cosmetic.notify.connected_cloud.signed_in", user.getName())
						: Component.translatable("orbit_cosmetic.notify.connected_cloud.development"));
				}
				publish(Minecraft.getInstance());
				query(Minecraft.getInstance());
			});
		});
	}

	/**
	 * Signs in with Minecraft's session check. The game tells Mojang it joins the backend's one-time server ID;
	 * the access token only ever goes to Mojang. Runs on the worker thread.
	 */
	private boolean signIn(Minecraft minecraft, net.minecraft.client.User user, BackendClient backend) {
		if (user == null || user.getAccessToken() == null || user.getAccessToken().isBlank() || user.getProfileId() == null) {
			return false;
		}
		try {
			String serverId = backend.challenge();
			minecraft.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
			BackendClient.Session created = backend.createSession(user.getName(), serverId);
			signedInAs = created.player();
			OrbitClient.LOGGER.info("Signed in to the Orbit backend as {}", created.name());
			applyBan(backend, created.banned(), created.bannedUntil());
			return true;
		} catch (com.mojang.authlib.exceptions.AuthenticationException e) {
			OrbitClient.LOGGER.debug("Mojang refused the session check: {}", e.getMessage());
		} catch (IOException e) {
			OrbitClient.LOGGER.debug("Backend sign-in failed: {}", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			OrbitClient.LOGGER.debug("Backend sign-in failed", e);
		}
		backend.signOut();
		signedInAs = null;
		return false;
	}

	/**
	 * Records the ban state the backend reported, announcing changes. While banned, the featured catalog is
	 * fetched so featured cosmetics can still be shared. Runs on the worker thread.
	 */
	private void applyBan(BackendClient backend, boolean isBanned, double until) {
		boolean wasBanned = banned;
		banned = isBanned;
		bannedUntil = until;
		if (isBanned) {
			try {
				featuredHashes = backend.featured().stream().map(FeaturedCosmetic::sha256).collect(java.util.stream.Collectors.toUnmodifiableSet());
			} catch (IOException e) {
				OrbitClient.LOGGER.debug("Could not load the featured catalog: {}", e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if (isBanned && !wasBanned) {
			Component when = until > 0
				? Component.translatable("orbit_cosmetic.notify.cloud_banned.until", DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond((long) until)))
				: Component.translatable("orbit_cosmetic.notify.cloud_banned.permanent");
			Notifications.error(Component.translatable("orbit_cosmetic.notify.cloud_banned"), when);
			Minecraft.getInstance().execute(this::markDirty);
		} else if (!isBanned && wasBanned) {
			Notifications.success(Component.translatable("orbit_cosmetic.notify.cloud_unbanned"), null);
			Minecraft.getInstance().execute(this::markDirty);
		}
	}

	/** Asks the backend for the current ban state (signed-in backends). Worker thread. */
	private void refreshBan(BackendClient backend) {
		if (!backend.signedIn()) {
			// Development backends have no sessions: a ban is known from refusals, and ends at its expiry.
			if (banned && bannedUntil > 0 && System.currentTimeMillis() / 1000.0 > bannedUntil) {
				applyBan(backend, false, 0);
			}
			return;
		}
		try {
			BackendClient.Session session = backend.me();
			applyBan(backend, session.banned(), session.bannedUntil());
		} catch (IOException e) {
			OrbitClient.LOGGER.debug("Could not refresh the ban state: {}", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** True while banned from Orbit Cloud (only featured cosmetics are shared). */
	public boolean isBanned() {
		return banned;
	}

	/** Shows a problem with a cosmetic once per connection. */
	private void announceOnce(String key, Runnable show) {
		if (announced.add(key)) {
			show.run();
		}
	}

	// ---------------------------------------------------------------- publishing

	private void publish(Minecraft minecraft) {
		if (minecraft.player == null) {
			return;
		}
		if (authRequired && signedInAs == null) {
			return; // not signed in: viewing only
		}
		// Signed-in presence is stored under the real account; development backends use the in-game UUID.
		UUID player = authRequired ? signedInAs : minecraft.player.getUUID();
		String name = minecraft.player.getGameProfile().name();
		net.minecraft.client.User user = minecraft.getUser();
		if (banned && bannedUntil > 0 && System.currentTimeMillis() / 1000.0 > bannedUntil) {
			BackendClient backend = client;
			worker.execute(() -> refreshBan(backend));
		}
		List<LocalCosmetic> worn = new ArrayList<>();
		Set<String> allowedWhileBanned = featuredHashes;
		for (LocalCosmetic entry : orbit.library().loaded()) {
			if (entry.serverRejection() == null && worn.size() < OrbitProtocol.MAX_EQUIPPED) {
				if (banned && !allowedWhileBanned.contains(entry.asset().hex())) {
					continue; // banned: featured cosmetics only
				}
				worn.add(entry);
			}
		}
		// Capture everything on the game thread; the worker only does I/O.
		List<EquippedCosmetic> equipped = new ArrayList<>();
		Map<AssetId, byte[]> bytes = new HashMap<>();
		Map<AssetId, String> names = new HashMap<>();
		for (LocalCosmetic entry : worn) {
			equipped.add(entry.toEquipped());
			bytes.put(entry.asset(), entry.bytes());
			names.put(entry.asset(), entry.displayName());
		}
		BackendClient current = client;
		publishedAs = player;
		worker.execute(() -> {
			List<EquippedCosmetic> available = new ArrayList<>();
			for (EquippedCosmetic cosmetic : equipped) {
				String sha = cosmetic.asset().hex();
				byte[] data = bytes.get(cosmetic.asset());
				String displayName = names.get(cosmetic.asset());
				try {
					if (!uploaded.contains(sha) && !current.hasAsset(sha)) {
						if (data == null || data.length > MAX_UPLOAD_BYTES) {
							warnOnceAsync("\"" + displayName + "\" is larger than the backend accepts (1 MiB) and is only visible to you");
							announceOnce(sha + ":too_large", () -> Notifications.error(Component.translatable("orbit_cosmetic.notify.too_big", displayName),
								Component.translatable("orbit_cosmetic.notify.too_big.detail", MAX_UPLOAD_BYTES / 1024)));
							continue;
						}
						if (current.upload(sha, displayName, data)) {
							Notifications.success(Component.translatable("orbit_cosmetic.notify.uploaded_cloud", displayName), Component.translatable("orbit_cosmetic.notify.uploaded_cloud.detail"));
						}
					}
					uploaded.add(sha);
					available.add(cosmetic);
				} catch (BackendClient.BackendException e) {
					uploadRefused(current, sha, displayName, e);
				} catch (IOException e) {
					warnOnceAsync("Could not upload \"" + displayName + "\" to the Orbit backend: " + e.getMessage());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			String equip = Base64.getEncoder().encodeToString(OrbitProtocol.encode(new OrbitMessage.Equip(available)));
			try {
				current.putPresence(player, name, OrbitProtocol.VERSION, equip);
			} catch (BackendClient.BackendException e) {
				// Sessions expire; sign in again once and retry.
				if (e.status == 401 && authRequired && signIn(minecraft, user, current)) {
					quietly(() -> current.putPresence(player, name, OrbitProtocol.VERSION, equip));
				} else if ("blocked".equals(e.code)) {
					applyBan(current, true, e.banExpires);
				} else {
					warnOnceAsync("Orbit backend refused the presence update: " + e.getMessage());
				}
			} catch (IOException e) {
				warnOnceAsync("Orbit backend request failed: " + e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/** Explains why the backend refused an upload. Worker thread. */
	private void uploadRefused(BackendClient backend, String sha, String displayName, BackendClient.BackendException e) {
		switch (e.code) {
			case "blocked" -> applyBan(backend, true, e.banExpires);
			case "too_large" -> announceOnce(sha + ":too_large", () -> Notifications.error(Component.translatable("orbit_cosmetic.notify.too_big", displayName),
				Component.translatable("orbit_cosmetic.notify.too_big.detail", MAX_UPLOAD_BYTES / 1024)));
			case "unsupported_file_type", "empty_file", "invalid_json", "invalid_encoding", "too_deep", "invalid_structure", "too_complex" ->
				announceOnce(sha + ":format", () -> Notifications.error(Component.translatable("orbit_cosmetic.notify.wrong_format", displayName), Component.literal(e.detail)));
			case "quota_exceeded", "storage_full" ->
				announceOnce(sha + ":quota", () -> Notifications.warning(Component.translatable("orbit_cosmetic.notify.cloud_rejected", displayName), Component.literal(e.detail)));
			default -> warnOnceAsync("Could not upload \"" + displayName + "\" to the Orbit backend: " + e.getMessage());
		}
	}

	// ---------------------------------------------------------------- Orbit Cloud features (any server)

	/** Loads the featured catalog. Callbacks run on the game thread. */
	public void loadFeatured(Consumer<List<FeaturedCosmetic>> done, Consumer<String> failed) {
		BackendClient current = ensureClient();
		if (current == null) {
			failed.accept("Invalid backend URL");
			return;
		}
		worker.execute(() -> {
			try {
				List<FeaturedCosmetic> items = current.featured();
				Minecraft.getInstance().execute(() -> done.accept(items));
			} catch (IOException e) {
				Minecraft.getInstance().execute(() -> failed.accept(e.getMessage()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/** Downloads a featured cosmetic and checks it against its SHA-256. Callbacks run on the game thread. */
	public void downloadFeatured(FeaturedCosmetic item, int maxBytes, Consumer<byte[]> done, Consumer<String> failed) {
		BackendClient current = ensureClient();
		if (current == null) {
			failed.accept("Invalid backend URL");
			return;
		}
		worker.execute(() -> {
			try {
				byte[] bytes = current.download(item.sha256(), maxBytes);
				AssetId id = AssetId.tryParse(item.sha256());
				if (id == null || !id.matches(bytes)) {
					Minecraft.getInstance().execute(() -> failed.accept("The download does not match the catalog"));
					return;
				}
				Minecraft.getInstance().execute(() -> done.accept(bytes));
			} catch (IOException e) {
				Minecraft.getInstance().execute(() -> failed.accept(e.getMessage()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/**
	 * Reports a player to Orbit Cloud, signing in with the Minecraft account first if needed.
	 * {@code done} gets true for a new report and false if it replaced an earlier one. Callbacks run on the game thread.
	 */
	public void report(UUID player, String name, String reason, String note, List<String> cosmetics, Consumer<Boolean> done, Consumer<String> failed) {
		BackendClient current = ensureClient();
		Minecraft minecraft = Minecraft.getInstance();
		if (current == null || minecraft.player == null) {
			failed.accept("Invalid backend URL");
			return;
		}
		net.minecraft.client.User user = minecraft.getUser();
		UUID self = minecraft.player.getUUID();
		worker.execute(() -> {
			try {
				String auth = current.health();
				if (auth == null) {
					minecraft.execute(() -> failed.accept(Component.translatable("orbit_cosmetic.report.unreachable").getString()));
					return;
				}
				boolean accounts = "mojang".equals(auth);
				if (accounts && !current.signedIn() && !signIn(minecraft, user, current)) {
					minecraft.execute(() -> failed.accept(Component.translatable("orbit_cosmetic.report.sign_in").getString()));
					return;
				}
				boolean created;
				try {
					created = current.report(player, name, reason, note, cosmetics, accounts ? null : self);
				} catch (BackendClient.BackendException e) {
					if (e.status != 401 || !accounts || !signIn(minecraft, user, current)) {
						throw e;
					}
					created = current.report(player, name, reason, note, cosmetics, null);
				}
				boolean result = created;
				minecraft.execute(() -> done.accept(result));
			} catch (BackendClient.BackendException e) {
				minecraft.execute(() -> failed.accept(e.detail.isEmpty() ? e.getMessage() : e.detail));
			} catch (IOException e) {
				minecraft.execute(() -> failed.accept(Component.translatable("orbit_cosmetic.report.unreachable").getString()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	// ---------------------------------------------------------------- discovering other players

	private void query(Minecraft minecraft) {
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null || minecraft.player == null) {
			return;
		}
		UUID self = minecraft.player.getUUID();
		List<UUID> players = new ArrayList<>();
		List<String> names = new ArrayList<>();
		Map<String, UUID> byName = new HashMap<>();
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			UUID id = info.getProfile().id();
			if (!id.equals(self) && players.size() < MAX_QUERY_PLAYERS) {
				players.add(id);
				String name = info.getProfile().name();
				if (name != null && !name.isEmpty()) {
					names.add(name);
					byName.put(name.toLowerCase(java.util.Locale.ROOT), id);
				}
			}
		}
		if (players.isEmpty()) {
			removeBackendPlayers();
			return;
		}
		BackendClient current = client;
		queryInFlight = true;
		worker.execute(() -> {
			List<BackendClient.PresenceEntry> entries;
			try {
				entries = current.queryPresence(players, names);
			} catch (IOException e) {
				entries = null;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			List<BackendClient.PresenceEntry> result = entries;
			Minecraft.getInstance().execute(() -> {
				queryInFlight = false;
				if (active && result != null) {
					apply(result, Set.copyOf(players), byName);
				}
			});
		});
	}

	private void apply(List<BackendClient.PresenceEntry> entries, Set<UUID> asked, Map<String, UUID> byName) {
		Set<UUID> seen = new HashSet<>();
		UUID self = signedInAs;
		for (BackendClient.PresenceEntry backendEntry : entries) {
			// Map the entry to the player as this server knows them: same UUID on online-mode servers, the same
			// name on offline-mode servers (where in-game UUIDs differ from the real account's).
			UUID target = asked.contains(backendEntry.player()) ? backendEntry.player() : byName.get(backendEntry.name().toLowerCase(java.util.Locale.ROOT));
			if (target == null || backendEntry.player().equals(self) || backendEntry.protocol() != OrbitProtocol.VERSION || backendEntry.equip().length() > 48_000) {
				continue;
			}
			BackendClient.PresenceEntry entry = new BackendClient.PresenceEntry(target, backendEntry.name(), backendEntry.protocol(), backendEntry.equip());
			List<EquippedCosmetic> cosmetics;
			try {
				byte[] payload = Base64.getDecoder().decode(entry.equip());
				if (!(OrbitProtocol.decodeServerbound(payload) instanceof OrbitMessage.Equip equip)) {
					continue;
				}
				cosmetics = equip.cosmetics();
			} catch (IllegalArgumentException | ProtocolException e) {
				continue; // malformed data from the backend is ignored
			}
			seen.add(entry.player());
			backendPlayers.add(entry.player());
			session.applyRemote(entry.player(), true, cosmetics);
		}
		for (UUID player : List.copyOf(backendPlayers)) {
			if (!seen.contains(player)) {
				backendPlayers.remove(player);
				session.removeRemote(player);
			}
		}
		// Retry assets that are not loaded yet (for example after a failed download).
		for (UUID player : backendPlayers) {
			var wearer = session.remoteWearers().get(player);
			if (wearer != null) {
				wearer.worn().forEach(worn -> session.ensureAsset(worn.asset()));
			}
		}
	}

	// ---------------------------------------------------------------- downloads

	/** Downloads an asset worn by another player. Called by {@link ClientSession} when it is not cached. */
	void requestAsset(AssetId id) {
		long now = System.currentTimeMillis();
		if (!active || downloading.contains(id) || retryAfter.getOrDefault(id, 0L) > now) {
			return;
		}
		downloading.add(id);
		BackendClient current = client;
		int maxBytes = session.limits().maxFileBytes();
		worker.execute(() -> {
			byte[] bytes;
			try {
				bytes = current.download(id.hex(), maxBytes);
			} catch (IOException e) {
				bytes = null;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			byte[] result = bytes;
			Minecraft.getInstance().execute(() -> {
				downloading.remove(id);
				if (!active) {
					return;
				}
				if (result == null || !id.matches(result)) {
					retryAfter.put(id, System.currentTimeMillis() + DOWNLOAD_RETRY_MS);
					return;
				}
				session.acceptAsset(id, result);
			});
		});
	}

	public void shutdown() {
		worker.shutdownNow();
	}

	// ---------------------------------------------------------------- helpers

	private interface Call {
		void run() throws IOException, InterruptedException;
	}

	private void quietly(Call call) {
		try {
			call.run();
		} catch (IOException e) {
			warnOnceAsync("Orbit backend request failed: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void warnOnceAsync(String message) {
		Minecraft.getInstance().execute(() -> warnOnce(message));
	}

	private void warnOnce(String message) {
		if (!warned) {
			warned = true;
			OrbitClient.LOGGER.warn(message);
		} else {
			OrbitClient.LOGGER.debug(message);
		}
	}
}

package dev.orbit.cosmetic.client.net;

import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.render.CosmeticRegistry;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.ChunkAssembler;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import dev.orbit.cosmetic.common.protocol.OrbitMessage;
import dev.orbit.cosmetic.common.protocol.OrbitMessage.*;
import dev.orbit.cosmetic.common.protocol.OrbitProtocol;
import dev.orbit.cosmetic.common.protocol.PlayerFlags;
import dev.orbit.cosmetic.common.protocol.ProtocolException;
import dev.orbit.cosmetic.common.protocol.ServerPolicy;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client side of the Orbit protocol for the current connection.
 *
 * <p>Nothing is sent until the server announces the Orbit channel, so servers without Orbit never see Orbit
 * traffic. Remote cosmetics are evaluated locally from synchronized state; only configuration changes and
 * asset bytes (once per asset, then cached on disk) travel over the network.
 */
public final class ClientSession {
	private static final int MAX_PARALLEL_DOWNLOADS = 4;
	private static final long DOWNLOAD_TIMEOUT_MS = 30_000;
	private static final long RETRY_DELAY_MS = 60_000;
	private static final int EQUIP_DEBOUNCE_TICKS = 10;

	private final OrbitClient orbit;
	private final AssetCache cache;
	/** Fallback for servers without Orbit; never used while an Orbit server is connected. */
	private BackendSync backend;
	private boolean helloSent;
	private boolean orbitServer;
	private ServerPolicy policy;
	private final Map<UUID, Wearer> remote = new HashMap<>();
	private final Map<UUID, List<EquippedCosmetic>> remoteConfig = new HashMap<>();
	private final Map<AssetId, ChunkAssembler> downloads = new HashMap<>();
	private final Map<AssetId, Long> requested = new HashMap<>();
	private final Map<AssetId, Long> retryAfter = new HashMap<>();
	private final Set<AssetId> readingCache = new HashSet<>();
	private final Deque<Upload> uploads = new ArrayDeque<>();
	private final Set<AssetId> reportedRejections = new HashSet<>();
	private int equipCountdown = -1;
	private int lastFlags = -1;
	private long ticks;

	private record Upload(AssetId asset, List<byte[]> chunks, int totalSize, int[] next) {
	}

	public ClientSession(OrbitClient orbit, AssetCache cache) {
		this.orbit = orbit;
		this.cache = cache;
	}

	public void setBackend(BackendSync backend) {
		this.backend = backend;
	}

	// ---------------------------------------------------------------- state queries

	/** True once an Orbit server welcomed this client. */
	public boolean isOrbitServer() {
		return orbitServer;
	}

	public ServerPolicy policy() {
		return policy;
	}

	/** Whether the local player's own cosmetics may be shown (server policy is authoritative when present). */
	public boolean localCosmeticsAllowed() {
		return !orbitServer || policy != null && policy.enabled() && policy.canWear();
	}

	public boolean animationsAllowed() {
		return !orbitServer || policy == null || policy.animationsAllowed();
	}

	public Map<UUID, Wearer> remoteWearers() {
		return remote;
	}

	public Set<AssetId> assetsInUse() {
		Set<AssetId> assets = new HashSet<>();
		for (Wearer wearer : remote.values()) {
			for (Wearer.Worn worn : wearer.worn()) {
				assets.add(worn.asset());
			}
		}
		return assets;
	}

	public int pendingDownloads() {
		return downloads.size() + requested.size();
	}

	// ---------------------------------------------------------------- connection lifecycle

	public void onJoin() {
		reset();
		trySendHello();
	}

	/** The server announced channels it can receive; Paper and Fabric servers announce Orbit's this way. */
	public void onChannelsRegistered() {
		trySendHello();
	}

	public void onDisconnect() {
		reset();
	}

	private void reset() {
		helloSent = false;
		orbitServer = false;
		policy = null;
		remote.clear();
		remoteConfig.clear();
		downloads.clear();
		requested.clear();
		retryAfter.clear();
		uploads.clear();
		reportedRejections.clear();
		equipCountdown = -1;
		lastFlags = -1;
		orbit.library().clearServerRejections();
	}

	private void trySendHello() {
		if (helloSent || !ClientPlayNetworking.canSend(OrbitPayload.TYPE)) {
			return;
		}
		helloSent = true;
		String version = FabricLoader.getInstance().getModContainer(OrbitClient.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		send(new Hello(OrbitProtocol.VERSION, version));
	}

	private void send(OrbitMessage message) {
		if (ClientPlayNetworking.canSend(OrbitPayload.TYPE)) {
			ClientPlayNetworking.send(new OrbitPayload(OrbitProtocol.encode(message)));
		}
	}

	/** Called when the library or animation settings change; sends the configuration shortly after. */
	public void markEquipDirty() {
		if (orbitServer) {
			equipCountdown = EQUIP_DEBOUNCE_TICKS;
		}
	}

	// ---------------------------------------------------------------- receiving

	public void handle(byte[] payload) {
		OrbitMessage message;
		try {
			message = OrbitProtocol.decodeClientbound(payload);
		} catch (ProtocolException e) {
			OrbitClient.LOGGER.debug("Ignoring malformed Orbit message: {}", e.getMessage());
			return;
		}
		switch (message) {
			case Welcome welcome -> onWelcome(welcome);
			case PolicyUpdate update -> onPolicy(update.policy());
			case PlayerCosmetics cosmetics -> onPlayerCosmetics(cosmetics);
			case PlayerRemove remove -> {
				remote.remove(remove.player());
				remoteConfig.remove(remove.player());
			}
			case AssetChunk chunk -> onAssetChunk(chunk);
			case AssetUnavailable unavailable -> {
				requested.remove(unavailable.asset());
				downloads.remove(unavailable.asset());
				retryAfter.put(unavailable.asset(), System.currentTimeMillis() + RETRY_DELAY_MS);
			}
			case UploadRequest request -> onUploadRequest(request.asset());
			case UploadResult result -> onUploadResult(result);
			case PlayerState state -> {
				Wearer wearer = remote.get(state.player());
				if (wearer != null) {
					wearer.flags = state.flags();
				}
			}
			case Notice notice -> {
				Component title = Component.translatable("orbit_cosmetic.notify.server_message");
				Component text = Component.literal(notice.message());
				switch (notice.level()) {
					case Notice.ERROR -> Notifications.error(title, text);
					case Notice.WARNING -> Notifications.warning(title, text);
					default -> Notifications.info(title, text);
				}
			}
			default -> {
				// serverbound messages cannot be decoded here
			}
		}
	}

	private void onWelcome(Welcome welcome) {
		// An Orbit server is authoritative: stop the backend fallback before its first message is applied.
		if (backend != null) {
			backend.onOrbitServer();
		}
		orbitServer = true;
		policy = welcome.policy();
		Component connected = Component.translatable("orbit_cosmetic.notify.connected_server");
		if (!policy.enabled()) {
			Notifications.warning(connected, policy.message().isEmpty() ? Component.translatable("orbit_cosmetic.notice.disabled") : Component.literal(policy.message()));
		} else if (!policy.canWear()) {
			Notifications.error(Component.translatable("orbit_cosmetic.notify.player_banned"),
				policy.message().isEmpty() ? Component.translatable("orbit_cosmetic.notice.blocked") : Component.literal(policy.message()));
		} else {
			Notifications.success(connected, Component.translatable("orbit_cosmetic.notify.connected_server.detail"));
		}
		orbit.onPolicyChanged();
		if (policy.enabled() && policy.canWear()) {
			equipCountdown = 0;
		}
	}

	private void onPolicy(ServerPolicy updated) {
		ServerPolicy previous = policy;
		policy = updated;
		if (!updated.enabled()) {
			remote.clear();
			remoteConfig.clear();
			downloads.clear();
			requested.clear();
			uploads.clear();
		}
		boolean couldWear = previous != null && previous.enabled() && previous.canWear();
		boolean canWear = updated.enabled() && updated.canWear();
		if (canWear != couldWear || previous != null && previous.enabled() != updated.enabled()) {
			Component text = !updated.enabled()
				? Component.translatable("orbit_cosmetic.notice.disabled")
				: canWear ? Component.translatable("orbit_cosmetic.notice.allowed") : Component.translatable("orbit_cosmetic.notice.blocked");
			if (!updated.message().isEmpty() && !canWear) {
				text = Component.literal(updated.message());
			}
			if (!updated.enabled()) {
				Notifications.warning(Component.translatable("orbit_cosmetic.notify.server_disabled"), text);
			} else if (canWear) {
				Notifications.success(Component.translatable("orbit_cosmetic.notify.player_unbanned"), text);
			} else {
				Notifications.error(Component.translatable("orbit_cosmetic.notify.player_banned"), text);
			}
		}
		if (canWear && (!couldWear || previous.uploadsAllowed() != updated.uploadsAllowed())) {
			reportedRejections.clear();
			orbit.library().clearServerRejections();
			equipCountdown = 0;
		}
		orbit.onPolicyChanged();
	}

	private void onPlayerCosmetics(PlayerCosmetics message) {
		applyRemote(message.player(), message.showIcon(), message.cosmetics());
	}

	/** Sets what another player wears (from the Orbit server, or from the backend fallback). */
	public void applyRemote(UUID player, boolean showIcon, List<EquippedCosmetic> cosmetics) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && player.equals(minecraft.player.getUUID())) {
			return;
		}
		Wearer wearer = remote.computeIfAbsent(player, Wearer::new);
		wearer.showIcon = showIcon;
		remoteConfig.put(player, cosmetics);
		for (EquippedCosmetic cosmetic : cosmetics) {
			ensureAsset(cosmetic.asset());
		}
		wearer.setWorn(cosmetics);
	}

	public void removeRemote(UUID player) {
		remote.remove(player);
		remoteConfig.remove(player);
	}

	// ---------------------------------------------------------------- assets

	/** Makes sure a remote asset is being loaded from the library, the cache or the server. */
	public void ensureAsset(AssetId id) {
		CosmeticRegistry registry = orbit.registry();
		if (registry.isKnown(id) || readingCache.contains(id)) {
			return;
		}
		LocalCosmetic local = orbit.library().find(id);
		if (local != null && local.model() != null) {
			registry.prepare(id, local.model());
			return;
		}
		readingCache.add(id);
		Util.ioPool().execute(() -> {
			byte[] bytes = cache.has(id) ? cache.read(id) : null;
			Minecraft.getInstance().execute(() -> {
				readingCache.remove(id);
				if (bytes != null) {
					registry.prepare(id, bytes, limits());
				} else {
					requestDownload(id);
				}
			});
		});
	}

	private void requestDownload(AssetId id) {
		if (!orbitServer) {
			if (backend != null && backend.isActive() && orbit.config().downloadFromServers) {
				backend.requestAsset(id);
			}
			return;
		}
		if (policy == null || !policy.enabled() || !policy.downloadsAllowed() || !orbit.config().downloadFromServers) {
			return;
		}
		long now = System.currentTimeMillis();
		if (requested.containsKey(id) || retryAfter.getOrDefault(id, 0L) > now || requested.size() >= MAX_PARALLEL_DOWNLOADS) {
			return;
		}
		requested.put(id, now);
		send(new RequestAsset(id));
	}

	private void onAssetChunk(AssetChunk chunk) {
		AssetId id = chunk.asset();
		if (!requested.containsKey(id)) {
			return; // never asked for it
		}
		if (chunk.totalSize() > limits().maxFileBytes()) {
			requested.remove(id);
			downloads.remove(id);
			retryAfter.put(id, Long.MAX_VALUE);
			return;
		}
		ChunkAssembler assembler = downloads.computeIfAbsent(id, key -> new ChunkAssembler(key, chunk.totalSize(), chunk.count(), OrbitProtocol.DOWNLOAD_CHUNK_SIZE, System.currentTimeMillis()));
		if (!assembler.accept(chunk.index(), chunk.count(), chunk.totalSize(), chunk.data(), System.currentTimeMillis())) {
			downloads.remove(id);
			requested.remove(id);
			retryAfter.put(id, System.currentTimeMillis() + RETRY_DELAY_MS);
			return;
		}
		requested.put(id, System.currentTimeMillis());
		if (!assembler.isComplete()) {
			return;
		}
		downloads.remove(id);
		requested.remove(id);
		byte[] bytes = assembler.bytes();
		if (!id.matches(bytes)) {
			OrbitClient.LOGGER.warn("Discarding cosmetic {}: data does not match its hash", id.shortHex());
			retryAfter.put(id, System.currentTimeMillis() + RETRY_DELAY_MS);
			return;
		}
		acceptAsset(id, bytes);
	}

	/** Caches and loads asset bytes already verified against their hash. */
	public void acceptAsset(AssetId id, byte[] bytes) {
		long maxCache = orbit.config().maxCacheMegabytes * 1024L * 1024L;
		Util.ioPool().execute(() -> {
			cache.write(id, bytes);
			cache.trim(maxCache);
		});
		orbit.registry().prepare(id, bytes, limits());
	}

	public ModelLimits limits() {
		ModelLimits own = orbit.config().modelLimits();
		return policy == null ? own : own.strictest(policy.limits());
	}

	// ---------------------------------------------------------------- uploads

	private void onUploadRequest(AssetId id) {
		if (policy == null || !policy.uploadsAllowed()) {
			return;
		}
		LocalCosmetic local = orbit.library().find(id);
		if (local == null || !local.isLoaded() || local.bytes() == null) {
			return; // only cosmetics the player chose to wear are ever uploaded
		}
		for (Upload upload : uploads) {
			if (upload.asset().equals(id)) {
				return;
			}
		}
		byte[] bytes = local.bytes();
		uploads.addLast(new Upload(id, OrbitProtocol.split(bytes, OrbitProtocol.UPLOAD_CHUNK_SIZE), bytes.length, new int[1]));
	}

	private void onUploadResult(UploadResult result) {
		LocalCosmetic local = orbit.library().find(result.asset());
		String name = local != null ? local.displayName() : result.asset().shortHex();
		if (result.accepted()) {
			orbit.library().setServerRejection(result.asset(), null);
			Notifications.success(Component.translatable("orbit_cosmetic.notify.uploaded_server", name),
				result.message().isEmpty() ? Component.translatable("orbit_cosmetic.notify.uploaded_server.detail") : Component.literal(result.message()));
			return;
		}
		orbit.library().setServerRejection(result.asset(), result.message());
		orbit.onPolicyChanged();
		if (reportedRejections.add(result.asset())) {
			// The protocol carries the reason as text; servers word bans as "... banned ...".
			boolean banned = result.message().toLowerCase(java.util.Locale.ROOT).contains("banned");
			Component title = Component.translatable(banned ? "orbit_cosmetic.notify.model_banned" : "orbit_cosmetic.notify.model_rejected", name);
			if (banned) {
				Notifications.error(title, Component.literal(result.message()));
			} else {
				Notifications.warning(title, Component.literal(result.message()));
			}
		}
	}

	// ---------------------------------------------------------------- ticking

	public void tick(boolean localFlying) {
		ticks++;
		if (!orbitServer) {
			return;
		}
		if (equipCountdown >= 0 && equipCountdown-- == 0) {
			sendEquip();
		}
		Upload upload = uploads.peekFirst();
		if (upload != null) {
			int index = upload.next()[0]++;
			send(new UploadChunk(upload.asset(), upload.totalSize(), index, upload.chunks().size(), upload.chunks().get(index)));
			if (upload.next()[0] >= upload.chunks().size()) {
				uploads.removeFirst();
			}
		}
		int flags = localFlying ? PlayerFlags.FLYING : 0;
		if (flags != lastFlags && policy != null && policy.enabled()) {
			lastFlags = flags;
			send(new State(flags));
		}
		if (ticks % 20 == 0) {
			long now = System.currentTimeMillis();
			requested.entrySet().removeIf(entry -> {
				if (now - entry.getValue() > DOWNLOAD_TIMEOUT_MS) {
					downloads.remove(entry.getKey());
					retryAfter.put(entry.getKey(), now + RETRY_DELAY_MS);
					return true;
				}
				return false;
			});
			for (Wearer wearer : remote.values()) {
				for (Wearer.Worn worn : wearer.worn()) {
					if (!orbit.registry().isKnown(worn.asset())) {
						ensureAsset(worn.asset());
					}
				}
			}
		}
	}

	private void sendEquip() {
		if (policy == null || !policy.enabled() || !policy.canWear()) {
			return;
		}
		List<EquippedCosmetic> cosmetics = new ArrayList<>();
		for (LocalCosmetic entry : orbit.library().loaded()) {
			if (cosmetics.size() >= Math.min(policy.maxEquipped(), OrbitProtocol.MAX_EQUIPPED)) {
				break;
			}
			cosmetics.add(entry.toEquipped());
		}
		send(new Equip(cosmetics));
	}
}

package dev.orbit.cosmetic.common.server;

import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AnimationTrigger;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.BBModelParser;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.Issue;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.ChunkAssembler;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import dev.orbit.cosmetic.common.protocol.OrbitMessage;
import dev.orbit.cosmetic.common.protocol.OrbitMessage.*;
import dev.orbit.cosmetic.common.protocol.OrbitProtocol;
import dev.orbit.cosmetic.common.protocol.ProtocolException;
import dev.orbit.cosmetic.common.protocol.ServerPolicy;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Platform-independent server logic shared by the Fabric server mod and the Paper plugin.
 *
 * <p>Responsibilities: the authoritative policy per player, the equipped configuration of each Orbit-enabled
 * player, asset upload validation and storage, paced asset downloads, and broadcasting visibility changes.
 * Players who never send {@link Hello} (vanilla clients) are never sent anything.
 *
 * <p>Threading: every public method must be called on the main server thread. Parsing and disk access run on
 * the background executor and hand their results back with {@link ServerPlatform#runOnMainThread}.
 */
public final class OrbitServerCore {
	private static final long UPLOAD_TIMEOUT_MS = 60_000;
	private static final int MAX_PARALLEL_UPLOADS = 4;
	private static final int MAX_QUEUED_DOWNLOADS = 32;
	private static final int MAX_VIOLATIONS = 64;
	private static final int MAX_REMEMBERED_REJECTIONS = 512;

	private final ServerPlatform platform;
	private final Executor background;
	private final LongSupplier clock;
	private final Path configFile;
	private final AssetStore store;
	private final SecurityLog securityLog;
	private ServerConfig config;

	private final Map<UUID, Session> sessions = new LinkedHashMap<>();
	private final Map<AssetId, AssetSummary> summaries = new HashMap<>();
	private final Set<AssetId> validating = new HashSet<>();
	private final Map<AssetId, String> rejected = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<AssetId, String> eldest) {
			return size() > MAX_REMEMBERED_REJECTIONS;
		}
	};
	private int generation;
	private long ticks;

	/** What the server knows about a validated asset. */
	public record AssetSummary(String name, Set<Anchor> anchors, int animations, int bones, int faces, int bytes) {
	}

	private enum Status {
		ACCEPTED,
		PENDING,
		MISSING,
		REJECTED
	}

	public OrbitServerCore(ServerPlatform platform, Executor background, LongSupplier clock) {
		this.platform = platform;
		this.background = background;
		this.clock = clock;
		Path data = platform.dataDirectory();
		this.configFile = data.resolve("orbit-server.json");
		this.config = ServerConfig.load(configFile, platform.logger());
		this.store = new AssetStore(data.resolve("assets"), platform.logger());
		this.securityLog = new SecurityLog(data.resolve("security.log"), platform.logger());
	}

	public ServerConfig config() {
		return config;
	}

	public AssetStore store() {
		return store;
	}

	public SecurityLog securityLog() {
		return securityLog;
	}

	public OrbitAdmin admin() {
		return new OrbitAdmin(this);
	}

	// ================================================================ platform events

	/** Handles a raw payload from the Orbit channel. Never throws. */
	public void onPayload(UUID playerId, byte[] payload) {
		Session session = sessions.get(playerId);
		if (session != null && session.ignored) {
			return;
		}
		long now = clock.getAsLong();
		if (session != null && !session.messages.tryAcquire(1, now)) {
			violation(session, "message rate limit exceeded");
			return;
		}
		OrbitMessage message;
		try {
			message = OrbitProtocol.decodeServerbound(payload);
		} catch (ProtocolException e) {
			if (session != null) {
				violation(session, "malformed message: " + e.getMessage());
			} else {
				securityLog.record(describe(playerId), "malformed message before hello: " + e.getMessage());
			}
			return;
		}
		try {
			if (message instanceof Hello hello) {
				onHello(playerId, hello);
				return;
			}
			if (session == null || !session.compatible) {
				return;
			}
			switch (message) {
				case Equip equip -> onEquip(session, equip.cosmetics());
				case UploadChunk chunk -> onUploadChunk(session, chunk);
				case RequestAsset request -> onRequestAsset(session, request.asset());
				case State state -> onState(session, state.flags());
				default -> violation(session, "unexpected message " + message.getClass().getSimpleName());
			}
		} catch (RuntimeException e) {
			platform.logger().error("Error handling Orbit message from " + describe(playerId), e);
		}
	}

	public void onDisconnect(UUID playerId) {
		Session session = sessions.remove(playerId);
		if (session != null && session.compatible) {
			sendToOthers(session, new PlayerRemove(playerId));
		}
	}

	/** Call once per server tick: sends queued downloads within the bandwidth budget and expires uploads. */
	public void tick() {
		long now = clock.getAsLong();
		if (++ticks % 100 == 0) {
			refreshPermissions();
		}
		int budget = config.downloadKilobytesPerTick * 1024;
		boolean progress = true;
		while (budget > 0 && progress) {
			progress = false;
			for (Session session : sessions.values()) {
				Download download = session.downloads.peekFirst();
				if (download == null) {
					continue;
				}
				int index = download.next++;
				byte[] chunk = download.chunks.get(index);
				send(session, new AssetChunk(download.asset, download.totalSize, index, download.chunks.size(), chunk));
				budget -= chunk.length;
				progress = true;
				if (download.next >= download.chunks.size()) {
					session.downloads.removeFirst();
					session.queuedDownloads.remove(download.asset);
				}
				if (budget <= 0) {
					break;
				}
			}
		}
		for (Session session : sessions.values()) {
			session.uploads.values().removeIf(upload -> now - upload.lastActivity() > UPLOAD_TIMEOUT_MS);
		}
	}

	// ================================================================ messages

	private void onHello(UUID playerId, Hello hello) {
		Session existing = sessions.get(playerId);
		if (existing != null && existing.compatible) {
			send(existing, new Welcome(OrbitProtocol.VERSION, existing.policy));
			return;
		}
		Session session = new Session(playerId, platform.playerName(playerId), clock.getAsLong(), config);
		sessions.put(playerId, session);
		if (hello.protocolVersion() != OrbitProtocol.VERSION) {
			session.compatible = false;
			String text = "This server uses Orbit protocol " + OrbitProtocol.VERSION + " but your mod uses " + hello.protocolVersion() + ". Update Orbit Cosmetic to use cosmetics here.";
			send(session, new Welcome(OrbitProtocol.VERSION, ServerPolicy.disabled(text)));
			return;
		}
		session.policy = policyFor(playerId);
		send(session, new Welcome(OrbitProtocol.VERSION, session.policy));
		if (!config.enabled) {
			return;
		}
		for (Session other : sessions.values()) {
			if (other != session && other.compatible) {
				send(session, new PlayerCosmetics(other.id, showIcon(other), other.visible));
				if (other.flags != 0) {
					send(session, new PlayerState(other.id, other.flags));
				}
			}
		}
		broadcastCosmetics(session);
	}

	private void onEquip(Session session, List<EquippedCosmetic> cosmetics) {
		if (!config.enabled) {
			return;
		}
		if (!session.policy.canWear()) {
			if (!session.toldBlocked) {
				session.toldBlocked = true;
				send(session, new Notice(Notice.WARNING, config.blockedMessage));
			}
			return;
		}
		Map<AssetId, EquippedCosmetic> unique = new LinkedHashMap<>();
		for (EquippedCosmetic cosmetic : cosmetics) {
			unique.putIfAbsent(cosmetic.asset(), cosmetic);
		}
		List<EquippedCosmetic> requested = new ArrayList<>(unique.values());
		if (requested.size() > config.maxEquippedPerPlayer) {
			send(session, new Notice(Notice.WARNING, "This server allows at most " + config.maxEquippedPerPlayer + " cosmetics at once; the rest are hidden."));
			requested = new ArrayList<>(requested.subList(0, config.maxEquippedPerPlayer));
		}
		session.requested = List.copyOf(requested);
		session.awaitingUpload.retainAll(unique.keySet());
		for (EquippedCosmetic cosmetic : session.requested) {
			AssetId id = cosmetic.asset();
			Status status = status(id);
			if (status == Status.MISSING) {
				if (config.allowUploads) {
					if (session.awaitingUpload.add(id)) {
						send(session, new UploadRequest(id));
					}
				} else {
					send(session, new UploadResult(id, false, "This server does not accept new cosmetics."));
				}
			} else if (status == Status.REJECTED) {
				send(session, new UploadResult(id, false, rejectionReason(id)));
			}
		}
		refreshVisible(session);
	}

	private void onUploadChunk(Session session, UploadChunk chunk) {
		AssetId id = chunk.asset();
		if (!session.awaitingUpload.contains(id)) {
			violation(session, "unrequested upload of " + id.shortHex());
			return;
		}
		ModelLimits limits = config.modelLimits();
		if (chunk.totalSize() > limits.maxFileBytes()) {
			session.awaitingUpload.remove(id);
			session.uploads.remove(id);
			reject(id, "The cosmetic is larger than this server allows (" + limits.maxFileBytes() / 1024 + " KiB).");
			send(session, new UploadResult(id, false, rejectionReason(id)));
			return;
		}
		long now = clock.getAsLong();
		if (!session.uploadBytes.tryAcquire(chunk.data().length, now)) {
			session.awaitingUpload.remove(id);
			session.uploads.remove(id);
			violation(session, "upload bandwidth limit exceeded");
			send(session, new UploadResult(id, false, "Upload limit reached; try again in a minute."));
			return;
		}
		ChunkAssembler upload = session.uploads.get(id);
		if (upload == null) {
			if (session.uploads.size() >= MAX_PARALLEL_UPLOADS) {
				violation(session, "too many parallel uploads");
				return;
			}
			upload = new ChunkAssembler(id, chunk.totalSize(), chunk.count(), OrbitProtocol.UPLOAD_CHUNK_SIZE, now);
			session.uploads.put(id, upload);
		}
		if (!upload.accept(chunk.index(), chunk.count(), chunk.totalSize(), chunk.data(), now)) {
			violation(session, "inconsistent upload chunk for " + id.shortHex());
			session.uploads.remove(id);
			return;
		}
		if (!upload.isComplete()) {
			return;
		}
		session.uploads.remove(id);
		session.awaitingUpload.remove(id);
		byte[] bytes = upload.bytes();
		if (!id.matches(bytes)) {
			violation(session, "uploaded data does not match its hash " + id.shortHex());
			send(session, new UploadResult(id, false, "Upload corrupted (hash mismatch)."));
			return;
		}
		if (store.has(id) || validating.contains(id)) {
			return;
		}
		validate(id, bytes, session.id, true);
	}

	private void onRequestAsset(Session session, AssetId id) {
		if (!session.policy.downloadsAllowed()) {
			send(session, new AssetUnavailable(id, "Downloads are disabled on this server."));
			return;
		}
		if (!isDistributable(id)) {
			send(session, new AssetUnavailable(id, "Not available."));
			return;
		}
		if (session.queuedDownloads.contains(id)) {
			return;
		}
		if (session.queuedDownloads.size() >= MAX_QUEUED_DOWNLOADS) {
			violation(session, "too many queued downloads");
			return;
		}
		session.queuedDownloads.add(id);
		int generationAtRequest = generation;
		background.execute(() -> {
			byte[] bytes = store.read(id);
			platform.runOnMainThread(() -> {
				Session current = sessions.get(session.id);
				if (current != session) {
					return;
				}
				if (bytes == null || generationAtRequest != generation && !isDistributable(id)) {
					session.queuedDownloads.remove(id);
					send(session, new AssetUnavailable(id, "Not available."));
					return;
				}
				session.downloads.addLast(new Download(id, bytes.length, OrbitProtocol.split(bytes, OrbitProtocol.DOWNLOAD_CHUNK_SIZE)));
			});
		});
	}

	private void onState(Session session, int flags) {
		if (!session.stateUpdates.tryAcquire(1, clock.getAsLong())) {
			return;
		}
		if (flags == session.flags) {
			return;
		}
		session.flags = flags;
		if (config.enabled) {
			sendToOthers(session, new PlayerState(session.id, flags));
		}
	}

	// ================================================================ assets

	private Status status(AssetId id) {
		if (config.bannedAssets.contains(id.hex())) {
			return Status.REJECTED;
		}
		if (rejected.containsKey(id)) {
			return Status.REJECTED;
		}
		AssetSummary summary = summaries.get(id);
		if (summary != null) {
			if (violatesPolicy(summary) != null) {
				return Status.REJECTED;
			}
			if (config.requireApprovedAssets && !config.approvedAssets.contains(id.hex())) {
				return Status.PENDING;
			}
			return Status.ACCEPTED;
		}
		if (validating.contains(id)) {
			return Status.PENDING;
		}
		if (store.has(id)) {
			validateStored(id);
			return Status.PENDING;
		}
		return Status.MISSING;
	}

	private String rejectionReason(AssetId id) {
		if (config.bannedAssets.contains(id.hex())) {
			return "This cosmetic has been banned on this server.";
		}
		AssetSummary summary = summaries.get(id);
		if (summary != null) {
			String reason = violatesPolicy(summary);
			if (reason != null) {
				return reason;
			}
		}
		return rejected.getOrDefault(id, "Rejected by the server.");
	}

	private String violatesPolicy(AssetSummary summary) {
		Set<Anchor> allowed = config.anchorSet();
		for (Anchor anchor : summary.anchors()) {
			if (!allowed.contains(anchor)) {
				return "Cosmetics attached to " + anchor + " are not allowed on this server.";
			}
		}
		return null;
	}

	private void validateStored(AssetId id) {
		if (!validating.add(id)) {
			return;
		}
		int generationAtStart = generation;
		ModelLimits limits = config.modelLimits();
		background.execute(() -> {
			byte[] bytes = store.read(id);
			BBModelParser.Result result = bytes == null ? null : BBModelParser.parse(bytes, null, limits);
			int size = bytes == null ? 0 : bytes.length;
			platform.runOnMainThread(() -> finishValidation(id, result, size, null, false, generationAtStart));
		});
	}

	private void validate(AssetId id, byte[] bytes, UUID uploader, boolean storeOnSuccess) {
		validating.add(id);
		int generationAtStart = generation;
		ModelLimits limits = config.modelLimits();
		background.execute(() -> {
			BBModelParser.Result result = BBModelParser.parse(bytes, null, limits);
			boolean stored = !storeOnSuccess || result.ok() && store.write(id, bytes);
			platform.runOnMainThread(() -> {
				if (result.ok() && !stored) {
					validating.remove(id);
					notifyUploader(uploader, id, false, "The server could not store the cosmetic.");
					return;
				}
				finishValidation(id, result, bytes.length, uploader, storeOnSuccess, generationAtStart);
			});
		});
	}

	private void finishValidation(AssetId id, BBModelParser.Result result, int size, UUID uploader, boolean fromUpload, int generationAtStart) {
		validating.remove(id);
		if (generationAtStart != generation) {
			// Config was reloaded meanwhile; re-evaluate from scratch with the new limits.
			summaries.remove(id);
			if (fromUpload && store.has(id)) {
				validateStored(id);
			}
			return;
		}
		if (result == null) {
			reject(id, "The stored cosmetic is unreadable.");
		} else if (!result.ok()) {
			Issue first = result.errors().isEmpty() ? null : result.errors().getFirst();
			String reason = "Invalid cosmetic: " + (first == null ? "unknown error" : first.message());
			reject(id, reason);
			if (uploader != null) {
				securityLog.record(describe(uploader), "rejected upload " + id.shortHex() + ": " + reason);
			}
			if (!fromUpload) {
				store.delete(id);
			}
		} else {
			CosmeticModel model = result.model();
			summaries.put(id, new AssetSummary(model.name(), model.anchorsUsed(), model.animations().size(), model.bones().size(), model.quadCount(), size));
			if (fromUpload) {
				evictIfNeeded();
			}
		}
		if (uploader != null) {
			Status status = status(id);
			if (status == Status.REJECTED) {
				notifyUploader(uploader, id, false, rejectionReason(id));
			} else if (status == Status.PENDING && config.requireApprovedAssets) {
				notifyUploader(uploader, id, true, "Uploaded. An administrator must approve it before others can see it.");
			} else {
				notifyUploader(uploader, id, true, "");
			}
		}
		refreshWearers(id);
	}

	/** Tells everyone wearing a cosmetic that it was just banned, so their client can say so. */
	public void assetBanned(AssetId id) {
		for (Session session : sessions.values()) {
			if (!session.compatible) {
				continue;
			}
			for (EquippedCosmetic cosmetic : session.requested) {
				if (cosmetic.asset().equals(id)) {
					send(session, new UploadResult(id, false, rejectionReason(id)));
					break;
				}
			}
		}
	}

	private void notifyUploader(UUID uploader, AssetId id, boolean accepted, String message) {
		Session session = sessions.get(uploader);
		if (session != null) {
			send(session, new UploadResult(id, accepted, message));
		}
	}

	private void reject(AssetId id, String reason) {
		rejected.put(id, reason);
		summaries.remove(id);
	}

	private boolean isDistributable(AssetId id) {
		if (!store.has(id) || status(id) != Status.ACCEPTED) {
			return false;
		}
		if (config.approvedAssets.contains(id.hex())) {
			return true;
		}
		for (Session session : sessions.values()) {
			for (EquippedCosmetic cosmetic : session.visible) {
				if (cosmetic.asset().equals(id)) {
					return true;
				}
			}
		}
		return false;
	}

	private void evictIfNeeded() {
		Set<AssetId> pinned = new HashSet<>();
		for (String hex : config.approvedAssets) {
			pinned.add(new AssetId(hex));
		}
		for (Session session : sessions.values()) {
			for (EquippedCosmetic cosmetic : session.requested) {
				pinned.add(cosmetic.asset());
			}
		}
		for (AssetId evicted : store.evict(config.maxStoreMegabytes * 1024 * 1024, pinned)) {
			summaries.remove(evicted);
		}
	}

	// ================================================================ visibility

	private void refreshWearers(AssetId id) {
		for (Session session : sessions.values()) {
			for (EquippedCosmetic cosmetic : session.requested) {
				if (cosmetic.asset().equals(id)) {
					refreshVisible(session);
					break;
				}
			}
		}
	}

	private void refreshVisible(Session session) {
		List<EquippedCosmetic> visible = new ArrayList<>();
		if (config.enabled && session.policy.canWear()) {
			for (EquippedCosmetic cosmetic : session.requested) {
				if (status(cosmetic.asset()) != Status.ACCEPTED) {
					continue;
				}
				AssetSummary summary = summaries.get(cosmetic.asset());
				visible.add(sanitize(cosmetic, summary));
				store.touch(cosmetic.asset());
			}
		}
		boolean icon = showIcon(session);
		if (!visible.equals(session.visible) || icon != session.lastIcon) {
			session.visible = List.copyOf(visible);
			broadcastCosmetics(session);
		}
	}

	/**
	 * Drops animation settings the asset cannot use and physics links to parts it does not have, and disables
	 * all animations and physics if the server does not allow animations.
	 */
	private EquippedCosmetic sanitize(EquippedCosmetic cosmetic, AssetSummary summary) {
		int count = summary == null ? 0 : summary.animations();
		List<AnimationSettings> animations = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			AnimationSettings settings = i < cosmetic.animations().size() ? cosmetic.animations().get(i) : null;
			if (settings == null || !config.allowAnimations) {
				settings = new AnimationSettings(AnimationTrigger.NONE, null, 1, 0, false);
			}
			animations.add(settings);
		}
		List<PhysicsLink> physics = new ArrayList<>();
		if (config.allowAnimations && summary != null) {
			for (PhysicsLink link : cosmetic.physics()) {
				if (link.bone() < summary.bones() && link.active() && physics.stream().noneMatch(p -> p.bone() == link.bone())) {
					physics.add(link);
				}
			}
		}
		String name = BBModelParser.sanitizeName(cosmetic.name());
		return new EquippedCosmetic(cosmetic.asset(), name == null ? "" : name, animations, physics);
	}

	private void broadcastCosmetics(Session session) {
		session.lastIcon = showIcon(session);
		if (!config.enabled) {
			return;
		}
		sendToOthers(session, new PlayerCosmetics(session.id, session.lastIcon, session.visible));
	}

	private boolean showIcon(Session session) {
		return config.enabled && config.showNameplateIcons && !config.isBlocked(session.id);
	}

	ServerPolicy policyFor(UUID player) {
		if (!config.enabled) {
			return ServerPolicy.disabled(config.disabledMessage);
		}
		boolean blocked = config.isBlocked(player);
		boolean permitted = platform.hasUsePermission(player);
		boolean canWear = !blocked && permitted;
		String message = canWear ? "" : config.blockedMessage;
		return new ServerPolicy(true, canWear, config.allowUploads, config.allowDownloads, config.showNameplateIcons, config.allowAnimations,
			config.maxEquippedPerPlayer, config.anchorSet(), config.modelLimits(), message);
	}

	/**
	 * Saves the config, re-sends policies and updates what everyone can see. Called by admin commands and reloads.
	 *
	 * @param revalidate whether asset limits may have changed, so every stored asset must be validated again
	 */
	public void applyConfigChange(boolean revalidate) {
		boolean resyncAll = false;
		if (revalidate) {
			generation++;
			summaries.clear();
			rejected.clear();
		}
		config.normalize();
		config.save(configFile, platform.logger());
		for (Session session : sessions.values()) {
			if (!session.compatible) {
				continue;
			}
			ServerPolicy policy = policyFor(session.id);
			boolean wasEnabled = session.policy.enabled();
			if (!policy.equals(session.policy)) {
				session.policy = policy;
				send(session, new PolicyUpdate(policy));
			}
			if (policy.enabled() && !policy.canWear()) {
				// Blocked: forget the configuration. A server-wide disable keeps it so enabling restores it.
				session.requested = List.of();
				session.awaitingUpload.clear();
				session.uploads.clear();
			} else {
				session.toldBlocked = false;
			}
			if (!policy.downloadsAllowed()) {
				session.downloads.clear();
				session.queuedDownloads.clear();
			}
			if (policy.enabled() && !wasEnabled) {
				resyncAll = true;
			}
		}
		for (Session session : sessions.values()) {
			if (session.compatible) {
				refreshVisible(session);
			}
		}
		if (resyncAll && config.enabled) {
			for (Session target : sessions.values()) {
				if (!target.compatible) {
					continue;
				}
				for (Session other : sessions.values()) {
					if (other != target && other.compatible) {
						send(target, new PlayerCosmetics(other.id, showIcon(other), other.visible));
					}
				}
			}
		}
	}

	/** Re-reads the config file from disk. */
	public void reload() {
		config = ServerConfig.load(configFile, platform.logger());
		applyConfigChange(true);
	}

	/** Re-evaluates permissions of every Orbit player (permission plugins can change them at any time). */
	public void refreshPermissions() {
		for (UUID player : List.copyOf(sessions.keySet())) {
			refreshPlayer(player);
		}
	}

	// ================================================================ admin helpers

	Collection<Session> sessions() {
		return sessions.values();
	}

	Map<AssetId, AssetSummary> summaries() {
		return summaries;
	}

	String rejection(AssetId id) {
		return rejected.get(id);
	}

	/** Re-evaluates the given player's permission (e.g. after a permission plugin change). */
	public void refreshPlayer(UUID player) {
		Session session = sessions.get(player);
		if (session == null || !session.compatible) {
			return;
		}
		ServerPolicy policy = policyFor(player);
		if (!policy.equals(session.policy)) {
			session.policy = policy;
			send(session, new PolicyUpdate(policy));
			if (policy.enabled() && !policy.canWear()) {
				session.requested = List.of();
			}
			refreshVisible(session);
		}
	}

	ServerPlatform platform() {
		return platform;
	}

	// ================================================================ sending

	private void send(Session session, OrbitMessage message) {
		platform.send(session.id, OrbitProtocol.encode(message));
	}

	private void sendToOthers(Session source, OrbitMessage message) {
		byte[] payload = OrbitProtocol.encode(message);
		for (Session session : sessions.values()) {
			if (session != source && session.compatible && session.policy.enabled()) {
				platform.send(session.id, payload);
			}
		}
	}

	private void violation(Session session, String what) {
		session.violations++;
		if (session.violations <= 5 || session.violations % 50 == 0) {
			securityLog.record(describe(session.id), what);
		}
		if (session.violations > MAX_VIOLATIONS && !session.ignored) {
			session.ignored = true;
			session.uploads.clear();
			session.downloads.clear();
			securityLog.record(describe(session.id), "too many invalid messages; ignoring Orbit traffic from this player until they reconnect");
		}
	}

	String describe(UUID player) {
		String name = platform.playerName(player);
		return name == null ? player.toString() : name + " (" + player + ")";
	}

	// ================================================================ state

	static final class Session {
		final UUID id;
		final String name;
		final RateLimiter messages;
		final RateLimiter uploadBytes;
		final RateLimiter stateUpdates;
		boolean compatible = true;
		boolean ignored;
		boolean toldBlocked;
		boolean lastIcon;
		ServerPolicy policy = ServerPolicy.disabled("");
		List<EquippedCosmetic> requested = List.of();
		List<EquippedCosmetic> visible = List.of();
		int flags;
		int violations;
		final Set<AssetId> awaitingUpload = new LinkedHashSet<>();
		final Map<AssetId, ChunkAssembler> uploads = new HashMap<>();
		final Deque<Download> downloads = new ArrayDeque<>();
		final Set<AssetId> queuedDownloads = new HashSet<>();

		Session(UUID id, String name, long now, ServerConfig config) {
			this.id = id;
			this.name = name;
			this.messages = new RateLimiter(config.maxMessagesPerSecond * 2.0, config.maxMessagesPerSecond, now);
			double uploadPerMinute = config.maxUploadKilobytesPerMinute * 1024.0;
			this.uploadBytes = new RateLimiter(uploadPerMinute, uploadPerMinute / 60.0, now);
			this.stateUpdates = new RateLimiter(10, 5, now);
		}

		String displayName() {
			return Objects.requireNonNullElse(name, id.toString());
		}
	}

	private static final class Download {
		final AssetId asset;
		final int totalSize;
		final List<byte[]> chunks;
		int next;

		Download(AssetId asset, int totalSize, List<byte[]> chunks) {
			this.asset = asset;
			this.totalSize = totalSize;
			this.chunks = chunks;
		}
	}
}

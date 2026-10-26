package dev.orbit.cosmetic.common.protocol;

import java.util.List;
import java.util.UUID;

/** Every message on the {@code orbit_cosmetic:sync} channel. */
public sealed interface OrbitMessage {
	// ------------------------------------------------------------ client -> server

	/** Sent once after joining; the server only treats a player as Orbit-enabled after this. */
	record Hello(int protocolVersion, String modVersion) implements OrbitMessage {
	}

	/** The full equipped configuration (replaces the previous one). Empty means "wear nothing". */
	record Equip(List<EquippedCosmetic> cosmetics) implements OrbitMessage {
	}

	/** One chunk of an asset the server asked for with {@link UploadRequest}. */
	record UploadChunk(AssetId asset, int totalSize, int index, int count, byte[] data) implements OrbitMessage {
	}

	/** Asks the server for an asset the client does not have cached. */
	record RequestAsset(AssetId asset) implements OrbitMessage {
	}

	/** State flags (see {@link PlayerFlags}); sent only when they change. */
	record State(int flags) implements OrbitMessage {
	}

	// ------------------------------------------------------------ server -> client

	record Welcome(int protocolVersion, ServerPolicy policy) implements OrbitMessage {
	}

	record PolicyUpdate(ServerPolicy policy) implements OrbitMessage {
	}

	/** Another player's visible cosmetics (only assets the server has accepted). Empty clears them. */
	record PlayerCosmetics(UUID player, boolean showIcon, List<EquippedCosmetic> cosmetics) implements OrbitMessage {
	}

	record PlayerRemove(UUID player) implements OrbitMessage {
	}

	record AssetChunk(AssetId asset, int totalSize, int index, int count, byte[] data) implements OrbitMessage {
	}

	record AssetUnavailable(AssetId asset, String reason) implements OrbitMessage {
	}

	/** The server does not have this asset and would like the client to upload it. */
	record UploadRequest(AssetId asset) implements OrbitMessage {
	}

	record UploadResult(AssetId asset, boolean accepted, String message) implements OrbitMessage {
	}

	record PlayerState(UUID player, int flags) implements OrbitMessage {
	}

	/** A message to show the player (restrictions, errors). */
	record Notice(int level, String message) implements OrbitMessage {
		public static final int INFO = 0;
		public static final int WARNING = 1;
		public static final int ERROR = 2;
	}
}

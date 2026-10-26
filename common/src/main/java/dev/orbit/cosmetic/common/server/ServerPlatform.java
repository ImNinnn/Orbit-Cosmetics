package dev.orbit.cosmetic.common.server;

import java.nio.file.Path;
import java.util.UUID;

/** What the shared server logic needs from Fabric or Paper. All methods are called on the main thread. */
public interface ServerPlatform {
	/** Sends a raw Orbit payload. Implementations must drop it if the player cannot receive the channel. */
	void send(UUID player, byte[] payload);

	/** Permission check for wearing cosmetics ({@code orbit.use}); true by default. */
	boolean hasUsePermission(UUID player);

	/** @return the online player's name, or null */
	String playerName(UUID player);

	/** @return the UUID of an online player with this name (case-insensitive), or null */
	UUID findOnlinePlayer(String name);

	/** Directory for config, assets and logs. */
	Path dataDirectory();

	/** Runs a task on the main server thread (used to finish background asset work). */
	void runOnMainThread(Runnable task);

	OrbitLogger logger();
}

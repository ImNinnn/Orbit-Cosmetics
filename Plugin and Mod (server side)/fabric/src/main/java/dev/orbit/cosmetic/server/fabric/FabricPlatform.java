package dev.orbit.cosmetic.server.fabric;

import dev.orbit.cosmetic.common.server.OrbitLogger;
import dev.orbit.cosmetic.common.server.ServerPlatform;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.UUID;

/** Connects the shared server core to a Fabric dedicated server. */
final class FabricPlatform implements ServerPlatform {
	/** Grant {@code orbit_cosmetic.use} = false (with a permission mod) to stop a player wearing cosmetics. */
	static final Identifier USE_PERMISSION = Identifier.fromNamespaceAndPath("orbit_cosmetic", "use");

	private final MinecraftServer server;
	private final Path dataDirectory;
	private final OrbitLogger logger;

	FabricPlatform(MinecraftServer server, Path dataDirectory, Logger slf4j) {
		this.server = server;
		this.dataDirectory = dataDirectory;
		this.logger = new OrbitLogger() {
			@Override
			public void info(String message) {
				slf4j.info(message);
			}

			@Override
			public void warn(String message) {
				slf4j.warn(message);
			}

			@Override
			public void error(String message, Throwable error) {
				slf4j.error(message, error);
			}
		};
	}

	@Override
	public void send(UUID player, byte[] payload) {
		ServerPlayer target = server.getPlayerList().getPlayer(player);
		if (target != null && ServerPlayNetworking.canSend(target, OrbitPayload.TYPE)) {
			ServerPlayNetworking.send(target, new OrbitPayload(payload));
		}
	}

	@Override
	public boolean hasUsePermission(UUID player) {
		ServerPlayer target = server.getPlayerList().getPlayer(player);
		return target == null || target.checkPermission(USE_PERMISSION, PermissionLevel.ALL);
	}

	@Override
	public String playerName(UUID player) {
		ServerPlayer target = server.getPlayerList().getPlayer(player);
		return target == null ? null : target.getName().getString();
	}

	@Override
	public UUID findOnlinePlayer(String name) {
		ServerPlayer target = server.getPlayerList().getPlayerByName(name);
		return target == null ? null : target.getUUID();
	}

	@Override
	public Path dataDirectory() {
		return dataDirectory;
	}

	@Override
	public void runOnMainThread(Runnable task) {
		server.execute(task);
	}

	@Override
	public OrbitLogger logger() {
		return logger;
	}
}

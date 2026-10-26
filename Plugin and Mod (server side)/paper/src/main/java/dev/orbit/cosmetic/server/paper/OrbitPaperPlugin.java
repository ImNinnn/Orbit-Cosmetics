package dev.orbit.cosmetic.server.paper;

import dev.orbit.cosmetic.common.protocol.OrbitProtocol;
import dev.orbit.cosmetic.common.server.OrbitAdmin;
import dev.orbit.cosmetic.common.server.OrbitLogger;
import dev.orbit.cosmetic.common.server.OrbitServerCore;
import dev.orbit.cosmetic.common.server.ServerPlatform;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Orbit Cosmetic for Paper servers. Uses the same plugin channel and protocol as the Fabric server mod, so the
 * Orbit client works the same on both. Players without the client mod join and play normally.
 */
public final class OrbitPaperPlugin extends JavaPlugin implements Listener, PluginMessageListener, ServerPlatform {
	private static final String USE_PERMISSION = "orbit.use";
	private static final String ADMIN_PERMISSION = "orbit.admin";

	private OrbitServerCore core;
	private ExecutorService background;
	private OrbitLogger orbitLogger;
	private boolean warnedChannels;

	@Override
	public void onEnable() {
		orbitLogger = new OrbitLogger() {
			@Override
			public void info(String message) {
				getLogger().info(message);
			}

			@Override
			public void warn(String message) {
				getLogger().warning(message);
			}

			@Override
			public void error(String message, Throwable error) {
				getLogger().log(Level.SEVERE, message, error);
			}
		};
		background = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "Orbit Cosmetic asset worker");
			thread.setDaemon(true);
			return thread;
		});
		core = new OrbitServerCore(this, background, System::currentTimeMillis);

		getServer().getMessenger().registerOutgoingPluginChannel(this, OrbitProtocol.CHANNEL);
		getServer().getMessenger().registerIncomingPluginChannel(this, OrbitProtocol.CHANNEL, this);
		getServer().getPluginManager().registerEvents(this, this);
		getServer().getScheduler().runTaskTimer(this, () -> core.tick(), 1L, 1L);

		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
			event.registrar().register("orbit", "Manage Orbit Cosmetic", List.of(), new AdminCommand()));

		getLogger().info("Orbit Cosmetic ready (" + (core.config().enabled ? "enabled" : "disabled") + "); data in " + getDataFolder());
	}

	@Override
	public void onDisable() {
		getServer().getMessenger().unregisterIncomingPluginChannel(this);
		getServer().getMessenger().unregisterOutgoingPluginChannel(this);
		core = null;
		if (background != null) {
			background.shutdown();
			try {
				background.awaitTermination(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (core != null && OrbitProtocol.CHANNEL.equals(channel)) {
			markListening(player);
			core.onPayload(player.getUniqueId(), message);
		}
	}

	/**
	 * Bukkit only delivers plugin messages on channels the client announced with {@code minecraft:register}
	 * during play. Fabric clients announce their channels while configuring the connection, which Paper does not
	 * carry over, so Paper would silently drop every Orbit reply. A client that sends on the Orbit channel
	 * certainly listens on it, so the channel is added to the player's set (there is no API for this; the
	 * implementation's {@code channels()} set is used and the problem is logged if that ever changes).
	 */
	private void markListening(Player player) {
		if (player.getListeningPluginChannels().contains(OrbitProtocol.CHANNEL)) {
			return;
		}
		try {
			Object channels = player.getClass().getMethod("channels").invoke(player);
			if (channels instanceof Set<?> set) {
				@SuppressWarnings("unchecked")
				Set<String> names = (Set<String>) set;
				names.add(OrbitProtocol.CHANNEL);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (!warnedChannels) {
				warnedChannels = true;
				getLogger().log(Level.WARNING, "Could not register the Orbit channel for " + player.getName()
					+ "; Orbit replies cannot be delivered on this Paper version", e);
			}
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		if (core != null) {
			core.onDisconnect(event.getPlayer().getUniqueId());
		}
	}

	// ---------------------------------------------------------------- ServerPlatform

	@Override
	public void send(UUID player, byte[] payload) {
		Player target = Bukkit.getPlayer(player);
		if (target != null && target.getListeningPluginChannels().contains(OrbitProtocol.CHANNEL)) {
			target.sendPluginMessage(this, OrbitProtocol.CHANNEL, payload);
		}
	}

	@Override
	public boolean hasUsePermission(UUID player) {
		Player target = Bukkit.getPlayer(player);
		return target == null || target.hasPermission(USE_PERMISSION);
	}

	@Override
	public String playerName(UUID player) {
		Player target = Bukkit.getPlayer(player);
		return target == null ? null : target.getName();
	}

	@Override
	public UUID findOnlinePlayer(String name) {
		Player target = Bukkit.getPlayerExact(name);
		return target == null ? null : target.getUniqueId();
	}

	@Override
	public Path dataDirectory() {
		return getDataFolder().toPath();
	}

	@Override
	public void runOnMainThread(Runnable task) {
		if (isEnabled()) {
			getServer().getScheduler().runTask(this, task);
		}
	}

	@Override
	public OrbitLogger logger() {
		return orbitLogger;
	}

	// ---------------------------------------------------------------- command

	private final class AdminCommand implements BasicCommand {
		@Override
		public void execute(CommandSourceStack source, String[] args) {
			if (core == null) {
				return;
			}
			for (String line : core.admin().execute(args)) {
				source.getSender().sendMessage(Component.text(line));
			}
		}

		@Override
		public Collection<String> suggest(CommandSourceStack source, String[] args) {
			if (core == null) {
				return List.of();
			}
			String[] completing = args.length == 0 ? new String[] {""} : args;
			return core.admin().complete(completing);
		}

		@Override
		public String permission() {
			return ADMIN_PERMISSION;
		}
	}

}

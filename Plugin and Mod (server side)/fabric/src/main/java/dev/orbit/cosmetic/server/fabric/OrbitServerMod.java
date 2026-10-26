package dev.orbit.cosmetic.server.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.orbit.cosmetic.common.server.OrbitAdmin;
import dev.orbit.cosmetic.common.server.OrbitServerCore;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Orbit Cosmetic server mod for Fabric dedicated servers. Vanilla clients can join normally: they never
 * announce the Orbit channel, so the server never sends them anything.
 */
public final class OrbitServerMod implements DedicatedServerModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("Orbit Cosmetic Server");
	/** Grant {@code orbit_cosmetic.admin} (or be an operator of level 3) to use {@code /orbit}. */
	static final Identifier ADMIN_PERMISSION = Identifier.fromNamespaceAndPath("orbit_cosmetic", "admin");

	private OrbitServerCore core;
	private ExecutorService background;

	@Override
	public void onInitializeServer() {
		PayloadTypeRegistry.serverboundPlay().register(OrbitPayload.TYPE, OrbitPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OrbitPayload.TYPE, OrbitPayload.CODEC);

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			background = Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "Orbit Cosmetic asset worker");
				thread.setDaemon(true);
				return thread;
			});
			Path data = FabricLoader.getInstance().getConfigDir().resolve("orbit_cosmetic");
			core = new OrbitServerCore(new FabricPlatform(server, data, LOGGER), background, System::currentTimeMillis);
			LOGGER.info("Orbit Cosmetic server ready ({}); data in {}", core.config().enabled ? "enabled" : "disabled", data);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			core = null;
			if (background != null) {
				background.shutdown();
				try {
					background.awaitTermination(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(OrbitPayload.TYPE, (payload, context) -> {
			if (core != null) {
				core.onPayload(context.player().getUUID(), payload.data());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (core != null) {
				core.onDisconnect(handler.getPlayer().getUUID());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (core != null) {
				core.tick();
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("orbit")
				.requires(PermissionPredicates.require(ADMIN_PERMISSION, PermissionLevel.ADMINS))
				.executes(context -> run(context, ""))
				.then(Commands.argument("arguments", StringArgumentType.greedyString())
					.suggests(this::suggest)
					.executes(context -> run(context, StringArgumentType.getString(context, "arguments"))))
		));
	}

	private int run(CommandContext<CommandSourceStack> context, String arguments) {
		if (core == null) {
			return 0;
		}
		List<String> lines = core.admin().execute(OrbitAdmin.split(arguments));
		for (String line : lines) {
			context.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}

	private CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		if (core == null) {
			return builder.buildFuture();
		}
		String input = builder.getRemaining();
		String[] parts = OrbitAdmin.split(input);
		boolean trailingSpace = input.endsWith(" ");
		String[] completing = parts;
		if (trailingSpace || parts.length == 0) {
			completing = java.util.Arrays.copyOf(parts, parts.length + 1);
			completing[completing.length - 1] = "";
		}
		int lastStart = trailingSpace || parts.length == 0 ? input.length() : input.lastIndexOf(parts[parts.length - 1]);
		SuggestionsBuilder offset = builder.createOffset(builder.getStart() + lastStart);
		for (String option : core.admin().complete(completing)) {
			offset.suggest(option);
		}
		return offset.buildFuture();
	}
}

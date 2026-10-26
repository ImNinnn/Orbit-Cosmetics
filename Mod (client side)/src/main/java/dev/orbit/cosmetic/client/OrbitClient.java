package dev.orbit.cosmetic.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.config.ClientConfig;
import dev.orbit.cosmetic.client.gui.CosmeticManagerScreen;
import dev.orbit.cosmetic.client.gui.PlayersScreen;
import dev.orbit.cosmetic.client.library.CosmeticLibrary;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.net.AssetCache;
import dev.orbit.cosmetic.client.net.BackendSync;
import dev.orbit.cosmetic.client.net.ClientSession;
import dev.orbit.cosmetic.client.net.OrbitPayload;
import dev.orbit.cosmetic.client.render.CosmeticLayer;
import dev.orbit.cosmetic.client.render.CosmeticRegistry;
import dev.orbit.cosmetic.client.render.CosmeticRenderData;
import dev.orbit.cosmetic.client.render.CosmeticRenderer;
import dev.orbit.cosmetic.client.render.RenderPiece;
import dev.orbit.cosmetic.common.anim.AnimationClock;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Client entrypoint and the hub connecting the library, networking, animation and rendering. */
public final class OrbitClient implements ClientModInitializer {
	public static final String MOD_ID = "orbit_cosmetic";
	public static final Logger LOGGER = LoggerFactory.getLogger("Orbit Cosmetic");

	/**
	 * The Orbit icon and a 1-unit gap, placed before the name inside the nameplate background. Mods that draw
	 * their own icon beside the measured name text (such as Essential) therefore end up to the left of it.
	 */
	private static final Component NAMEPLATE_ICON = Component.literal("\uE000\uE001")
		.withStyle(style -> style.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(MOD_ID, "nameplate"))).withColor(ChatFormatting.WHITE));

	private static OrbitClient instance;

	private ClientConfig config;
	private OrbitPaths paths;
	private CosmeticLibrary library;
	private CosmeticRegistry registry;
	private ClientSession session;
	private BackendSync backend;
	private AssetCache cache;
	private KeyMapping openManager;
	private Wearer localWearer;
	private LocalCosmetic preview;
	private long clientTicks;
	/** Animation time; follows the level's game time smoothly (see AnimationClock). */
	private final AnimationClock clock = new AnimationClock();
	private ClientLevel clockLevel;
	private final Set<Object> layeredRenderers = Collections.newSetFromMap(new WeakHashMap<>());

	public static OrbitClient get() {
		return instance;
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		Minecraft minecraft = Minecraft.getInstance();
		paths = new OrbitPaths(minecraft.gameDirectory.toPath());
		try {
			paths.create();
		} catch (IOException e) {
			LOGGER.error("Could not create {}", paths.root(), e);
		}
		config = ClientConfig.load(minecraft.gameDirectory.toPath().resolve("config").resolve("orbit_cosmetic.json"));
		registry = new CosmeticRegistry();
		cache = new AssetCache(paths.cache());
		library = new CosmeticLibrary(paths);
		session = new ClientSession(this, cache);
		backend = new BackendSync(this, session);
		session.setBackend(backend);
		library.addListener(() -> {
			updateLocalWearer();
			session.markEquipDirty();
			backend.markDirty();
		});

		PayloadTypeRegistry.serverboundPlay().register(OrbitPayload.TYPE, OrbitPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OrbitPayload.TYPE, OrbitPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(OrbitPayload.TYPE, (payload, context) -> session.handle(payload.data()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			backend.onJoin();
			session.onJoin();
			updateLocalWearer();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			backend.onDisconnect();
			session.onDisconnect();
			clockLevel = null;
			updateLocalWearer();
		});
		ServerboundPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
			if (channels.contains(OrbitPayload.TYPE.id())) {
				session.onChannelsRegistered();
			}
		});

		LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, renderer, helper, context) -> {
			// Player and mannequin renderers; only players ever get cosmetic data. Guard against double registration.
			if (renderer instanceof AvatarRenderer<?> avatar && layeredRenderers.add(avatar)) {
				helper.register(new CosmeticLayer(avatar));
			}
		});

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openManager = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.orbit_cosmetic.open_manager", InputConstants.KEY_O, category));

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			backend.onDisconnect();
			backend.shutdown();
			registry.releaseAll();
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof PauseScreen || screen instanceof TitleScreen) {
				Button button = Button.builder(Component.translatable("orbit_cosmetic.menu.button"), b -> client.gui.setScreen(new CosmeticManagerScreen(screen)))
					.bounds(4, 4, 80, 20)
					.tooltip(Tooltip.create(Component.translatable("orbit_cosmetic.menu.tooltip")))
					.build();
				Screens.getWidgets(screen).add(button);
				// The Players menu, on servers where Orbit players can see each other.
				if (screen instanceof PauseScreen && PlayersScreen.available()) {
					Screens.getWidgets(screen).add(Button.builder(Component.translatable("orbit_cosmetic.players.button"), b -> client.gui.setScreen(new PlayersScreen(screen)))
						.bounds(88, 4, 80, 20)
						.tooltip(Tooltip.create(Component.translatable("orbit_cosmetic.players.tooltip")))
						.build());
				}
			}
		});

		library.reload();
		LOGGER.info("Orbit Cosmetic ready; cosmetics folder: {}", paths.root());
	}

	public ClientConfig config() {
		return config;
	}

	public OrbitPaths paths() {
		return paths;
	}

	public CosmeticLibrary library() {
		return library;
	}

	public CosmeticRegistry registry() {
		return registry;
	}

	public ClientSession session() {
		return session;
	}

	public AssetCache cache() {
		return cache;
	}

	public BackendSync backend() {
		return backend;
	}

	public KeyMapping openManagerKey() {
		return openManager;
	}

	// ---------------------------------------------------------------- local wearer

	/** Shows a cosmetic on the local player while the manager is open, without loading it. */
	public void setPreview(LocalCosmetic entry) {
		preview = entry;
		updateLocalWearer();
	}

	public LocalCosmetic preview() {
		return preview;
	}

	public void onPolicyChanged() {
		updateLocalWearer();
	}

	private void updateLocalWearer() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			localWearer = null;
			return;
		}
		if (localWearer == null || !localWearer.id.equals(minecraft.player.getUUID())) {
			localWearer = new Wearer(minecraft.player.getUUID());
		}
		Map<AssetId, EquippedCosmetic> worn = new LinkedHashMap<>();
		if (session.localCosmeticsAllowed()) {
			for (LocalCosmetic entry : library.loaded()) {
				if (entry.serverRejection() == null) {
					worn.putIfAbsent(entry.asset(), entry.toEquipped());
				}
			}
		}
		if (preview != null && preview.isUsable()) {
			worn.putIfAbsent(preview.asset(), preview.toEquipped());
		}
		localWearer.setWorn(List.copyOf(worn.values()));
	}

	// ---------------------------------------------------------------- ticking

	private void tick(Minecraft minecraft) {
		while (openManager.consumeClick()) {
			if (minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new CosmeticManagerScreen(null));
			}
		}
		clientTicks++;
		LocalPlayer player = minecraft.player;
		boolean flying = player != null && player.getAbilities().flying;
		session.tick(flying);
		backend.tick(minecraft);
		if (player != null && minecraft.level != null) {
			if (localWearer == null || !localWearer.id.equals(player.getUUID())) {
				updateLocalWearer();
			}
			if (minecraft.level != clockLevel) {
				// Joined a server, changed dimension or switched subserver: a new level and timeline.
				clockLevel = minecraft.level;
				clock.reset();
			}
			clock.tick(minecraft.level.getGameTime());
			double time = clock.time();
			int generation = clock.generation();
			localWearer.tick(player, time, generation, flying, registry::get);
			double maxDistanceSq = (double) config.renderDistance * config.renderDistance;
			for (Wearer wearer : session.remoteWearers().values()) {
				Player other = minecraft.level.getPlayerByUUID(wearer.id);
				if (other != null && other.distanceToSqr(player) <= maxDistanceSq) {
					wearer.tick(other, time, generation, false, registry::get);
				}
			}
		}
		if (clientTicks % 100 == 0) {
			Set<AssetId> inUse = new HashSet<>(session.assetsInUse());
			if (localWearer != null) {
				localWearer.worn().forEach(worn -> inUse.add(worn.asset()));
			}
			for (LocalCosmetic entry : library.entries()) {
				if (entry.isLoaded() && entry.asset() != null) {
					inUse.add(entry.asset());
				}
			}
			registry.collect(inUse);
		}
	}

	// ---------------------------------------------------------------- rendering hooks

	/** Called from the player renderer after it extracted a player's render state. */
	public void extract(AbstractClientPlayer player, AvatarRenderState state, float partialTicks) {
		state.setData(CosmeticRenderData.KEY, null);
		Minecraft minecraft = Minecraft.getInstance();
		boolean local = minecraft.player != null && player.getUUID().equals(minecraft.player.getUUID());
		Wearer wearer = local ? localWearer : session.remoteWearers().get(player.getUUID());
		if (state.nameTag != null && config.showNameplateIcons && (local ? ownIconAllowed() : wearer != null && wearer.showIcon && remoteIconsAllowed())) {
			state.nameTag = Component.empty().append(NAMEPLATE_ICON).append(state.nameTag);
		}
		if (wearer == null) {
			return;
		}
		if (!config.renderCosmetics || wearer.worn().isEmpty() || !local && (!config.showOtherPlayers || config.isHidden(wearer.id))) {
			return;
		}
		double distanceSq = state.distanceToCameraSq;
		if (!local && distanceSq > (double) config.renderDistance * config.renderDistance) {
			return;
		}
		boolean animate = config.playAnimations && session.animationsAllowed() && distanceSq <= (double) config.animationDistance * config.animationDistance;
		List<RenderPiece> pieces = wearer.pieces(state, clock.at(partialTicks), partialTicks, animate, config.maxFacesPerPlayer, registry::get);
		if (!pieces.isEmpty()) {
			state.setData(CosmeticRenderData.KEY, new CosmeticRenderData(pieces));
		}
	}

	private boolean remoteIconsAllowed() {
		if (session.policy() == null) {
			// No Orbit server: players found through the backend fallback get the icon.
			return backend.isActive();
		}
		return session.policy().enabled() && session.policy().showNameplateIcons();
	}

	/** Your own icon follows the same server policy; without an Orbit server it is purely local. */
	private boolean ownIconAllowed() {
		return !session.isOrbitServer() || remoteIconsAllowed() && session.policy().canWear();
	}

	/** Whether the local player's own nameplate should be drawn (third person only, as other players see it). */
	public boolean shouldShowOwnNameplate(Entity entity) {
		Minecraft minecraft = Minecraft.getInstance();
		return config.showOwnNameplate
			&& entity == minecraft.player
			&& entity == minecraft.getCameraEntity()
			&& !minecraft.options.getCameraType().isFirstPerson()
			&& !minecraft.gui.hud.isHidden()
			&& !entity.isInvisible()
			&& !entity.isVehicle();
	}

	/** Called after the first-person hand was submitted; draws cosmetics attached to that arm. */
	public void renderFirstPersonArm(PlayerModel model, PoseStack poseStack, SubmitNodeCollector collector, int light, ModelPart arm) {
		if (!config.renderCosmetics || !config.showInFirstPerson || localWearer == null || localWearer.worn().isEmpty()) {
			return;
		}
		Anchor anchor = arm == model.rightArm ? Anchor.RIGHT_ARM : arm == model.leftArm ? Anchor.LEFT_ARM : null;
		if (anchor == null) {
			return;
		}
		float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		boolean animate = config.playAnimations && session.animationsAllowed();
		List<RenderPiece> pieces = localWearer.pieces(null, clock.at(partial), partial, animate, config.maxFacesPerPlayer, registry::get);
		if (pieces.isEmpty()) {
			return;
		}
		poseStack.pushPose();
		arm.translateAndRotate(poseStack);
		CosmeticRenderer.submit(pieces, null, poseStack, collector, light, anchor);
		poseStack.popPose();
	}
}

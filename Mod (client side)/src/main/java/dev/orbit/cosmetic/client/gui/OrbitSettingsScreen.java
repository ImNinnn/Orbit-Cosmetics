package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.config.ClientConfig;
import dev.orbit.cosmetic.common.model.ModelLimits;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.Locale;
import java.util.function.Consumer;

/** Local preferences. Server restrictions always take precedence over these. */
public final class OrbitSettingsScreen extends Screen {
	private final Screen parent;
	private long cacheBytes = -1;

	public OrbitSettingsScreen(Screen parent) {
		super(Component.translatable("orbit_cosmetic.settings.title"));
		this.parent = parent;
	}

	private static ClientConfig config() {
		return OrbitClient.get().config();
	}

	@Override
	protected void init() {
		ClientConfig config = config();
		int left = width / 2 - 155;
		int right = width / 2 + 5;
		int y = 32;
		toggle(left, y, "render", config.renderCosmetics, value -> config.renderCosmetics = value);
		toggle(right, y, "others", config.showOtherPlayers, value -> config.showOtherPlayers = value);
		y += 24;
		toggle(left, y, "first_person", config.showInFirstPerson, value -> config.showInFirstPerson = value);
		toggle(right, y, "animations", config.playAnimations, value -> config.playAnimations = value);
		y += 24;
		toggle(left, y, "icons", config.showNameplateIcons, value -> config.showNameplateIcons = value);
		toggle(right, y, "own_nameplate", config.showOwnNameplate, value -> config.showOwnNameplate = value);
		y += 24;
		toggle(left, y, "downloads", config.downloadFromServers, value -> config.downloadFromServers = value);
		toggle(right, y, "backend", config.useBackend, value -> config.useBackend = value);
		y += 24;
		toggle(left, y, "notifications", config.showNotifications, value -> config.showNotifications = value);
		y += 24;
		addRenderableWidget(new ValueSlider(left, y, 150, 8, 256, 8, config.renderDistance,
			value -> Component.translatable("orbit_cosmetic.settings.render_distance", (int) value),
			value -> {
				config.renderDistance = (int) value;
				save();
			}));
		addRenderableWidget(new ValueSlider(right, y, 150, 4, 256, 4, config.animationDistance,
			value -> Component.translatable("orbit_cosmetic.settings.animation_distance", (int) value),
			value -> {
				config.animationDistance = (int) value;
				save();
			}));
		y += 24;
		addRenderableWidget(new ValueSlider(left, y, 150, 500, ModelLimits.HARD.maxQuads(), 500, config.maxFacesPerPlayer,
			value -> Component.translatable("orbit_cosmetic.settings.max_faces", (int) value),
			value -> {
				config.maxFacesPerPlayer = (int) value;
				save();
			}));
		addRenderableWidget(new ValueSlider(right, y, 150, 16, 1024, 16, config.maxCacheMegabytes,
			value -> Component.translatable("orbit_cosmetic.settings.cache_size", (int) value),
			value -> {
				config.maxCacheMegabytes = (int) value;
				save();
			}));
		y += 24;
		addRenderableWidget(CycleButton.<ClientConfig.AttachMode>builder(mode -> Component.translatable("orbit_cosmetic.settings.attach." + mode.name().toLowerCase(Locale.ROOT)), config.attachMode)
			.withValues(ClientConfig.AttachMode.values())
			.withTooltip(mode -> Tooltip.create(Component.translatable("orbit_cosmetic.settings.attach." + mode.name().toLowerCase(Locale.ROOT) + ".tooltip")))
			.create(left, y, 150, 20, Component.translatable("orbit_cosmetic.settings.attach"), (button, value) -> {
				config.attachMode = value;
				save();
			}));
		addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.settings.clear_cache"), b -> {
			b.active = false;
			Util.ioPool().execute(() -> {
				int removed = OrbitClient.get().cache().clear();
				minecraft.execute(() -> {
					cacheBytes = 0;
					b.setMessage(Component.translatable("orbit_cosmetic.settings.cache_cleared", removed));
				});
			});
		}).bounds(right, y, 150, 20).tooltip(Tooltip.create(Component.translatable("orbit_cosmetic.settings.clear_cache.tooltip"))).build());

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());

		Util.ioPool().execute(() -> {
			long size = OrbitClient.get().cache().sizeBytes();
			minecraft.execute(() -> cacheBytes = size);
		});
	}

	private void toggle(int x, int y, String key, boolean value, Consumer<Boolean> setter) {
		addRenderableWidget(CycleButton.onOffBuilder(value)
			.withTooltip(v -> Tooltip.create(Component.translatable("orbit_cosmetic.settings." + key + ".tooltip")))
			.create(x, y, 150, 20, Component.translatable("orbit_cosmetic.settings." + key), (button, v) -> {
				setter.accept(v);
				save();
			}));
	}

	private static void save() {
		config().normalize();
		config().save();
	}

	@Override
	public void onClose() {
		save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		if (cacheBytes >= 0) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.settings.cache_usage", cacheBytes / 1024), width / 2, 32 + 24 * 8 + 6, 0xFFAAAAAA);
		}
	}
}

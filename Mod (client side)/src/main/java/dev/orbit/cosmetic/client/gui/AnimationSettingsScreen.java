package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AnimationTrigger;
import dev.orbit.cosmetic.common.anim.LoopMode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Assigns each animation of a cosmetic to a player state and sets how it plays. Animations named after a
 * state (IDLE, WALK, SPRINT, ...) start out assigned to it; others start unassigned. "Advanced" leads to the
 * movement physics of the cosmetic's parts.
 */
public final class AnimationSettingsScreen extends Screen {
	/** Title line (12) + two widget lines (22 + 20) + spacing. */
	private static final int ROW_HEIGHT = 60;

	private final Screen parent;
	private final LocalCosmetic cosmetic;
	private int page;

	public AnimationSettingsScreen(Screen parent, LocalCosmetic cosmetic) {
		super(Component.translatable("orbit_cosmetic.animations.title", cosmetic.displayName()));
		this.parent = parent;
		this.cosmetic = cosmetic;
	}

	private int rowsPerPage() {
		return Math.max(1, (height - 90) / ROW_HEIGHT);
	}

	@Override
	protected void init() {
		List<AnimationClip> clips = cosmetic.model() == null ? List.of() : cosmetic.model().animations();
		List<AnimationSettings> settings = cosmetic.animationSettings();
		int perPage = rowsPerPage();
		int pages = Math.max(1, (clips.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		int rowWidth = Math.min(420, width - 20);
		int left = (width - rowWidth) / 2;
		int columnWidth = (rowWidth - 8) / 3;

		for (int row = 0; row < perPage; row++) {
			int index = page * perPage + row;
			if (index >= clips.size()) {
				break;
			}
			int y = 40 + row * ROW_HEIGHT + 12;
			AnimationSettings current = settings.get(index);
			int i = index;

			boolean random = current.trigger() == AnimationTrigger.RANDOM;
			addRenderableWidget(CycleButton.<AnimationTrigger>builder(t -> Component.translatable("orbit_cosmetic.trigger." + t.name().toLowerCase(Locale.ROOT)), current.trigger())
				.withValues(AnimationTrigger.values())
				.withTooltip(t -> Tooltip.create(Component.translatable("orbit_cosmetic.trigger." + t.name().toLowerCase(Locale.ROOT) + ".tooltip")))
				.create(left, y, random ? columnWidth : columnWidth * 2 + 4, 20, Component.translatable("orbit_cosmetic.animations.trigger"), (button, value) -> {
					update(i, cosmetic.animationSettings().get(i).withTrigger(value));
					rebuildWidgets();
				}));
			if (random) {
				// Random animations always play once per occurrence; the wait range replaces the mode.
				addRenderableWidget(new ValueSlider(left + columnWidth + 4, y, columnWidth, 0, 120, 0.5, current.minWait(),
					value -> Component.translatable("orbit_cosmetic.animations.min_wait", formatSeconds(value)),
					value -> update(i, cosmetic.animationSettings().get(i).withMinWait((float) value))));
				addRenderableWidget(new ValueSlider(left + (columnWidth + 4) * 2, y, columnWidth, 0, 120, 0.5, current.maxWait(),
					value -> Component.translatable("orbit_cosmetic.animations.max_wait", formatSeconds(value)),
					value -> update(i, cosmetic.animationSettings().get(i).withMaxWait((float) value))));
			} else {
				addRenderableWidget(CycleButton.<LoopMode>builder(m -> Component.translatable("orbit_cosmetic.loop." + m.name().toLowerCase(Locale.ROOT)), current.loop())
					.withValues(LoopMode.values())
					.withTooltip(m -> Tooltip.create(Component.translatable("orbit_cosmetic.loop." + m.name().toLowerCase(Locale.ROOT) + ".tooltip")))
					.create(left + (columnWidth + 4) * 2, y, columnWidth, 20, Component.translatable("orbit_cosmetic.animations.loop"), (button, value) ->
						update(i, cosmetic.animationSettings().get(i).withLoop(value))));
			}

			int y2 = y + 22;
			addRenderableWidget(new ValueSlider(left, y2, columnWidth, AnimationSettings.MIN_SPEED, AnimationSettings.MAX_SPEED, 0.05, current.speed(),
				value -> Component.translatable("orbit_cosmetic.animations.speed", String.format(Locale.ROOT, "%.2f", value)),
				value -> update(i, cosmetic.animationSettings().get(i).withSpeed((float) value))));
			addRenderableWidget(new ValueSlider(left + columnWidth + 4, y2, columnWidth, 0, AnimationSettings.MAX_PRIORITY, 1, current.priority(),
				value -> Component.translatable("orbit_cosmetic.animations.priority", (int) value),
				value -> update(i, cosmetic.animationSettings().get(i).withPriority((int) value))));
			addRenderableWidget(CycleButton.onOffBuilder(current.interrupt())
				.withTooltip(value -> Tooltip.create(Component.translatable("orbit_cosmetic.animations.interrupt.tooltip")))
				.create(left + (columnWidth + 4) * 2, y2, columnWidth, 20, Component.translatable("orbit_cosmetic.animations.interrupt"), (button, value) ->
					update(i, cosmetic.animationSettings().get(i).withInterrupt(value))));
		}

		int footerY = height - 28;
		if (pages > 1) {
			addRenderableWidget(Button.builder(Component.literal("<"), b -> {
				page = Math.max(0, page - 1);
				rebuildWidgets();
			}).bounds(left, footerY, 20, 20).build()).active = page > 0;
			addRenderableWidget(Button.builder(Component.literal(">"), b -> {
				page = Math.min(pages - 1, page + 1);
				rebuildWidgets();
			}).bounds(left + 24, footerY, 20, 20).build()).active = page < pages - 1;
		}
		Button reset = addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.animations.reset"), b -> {
			OrbitClient.get().library().resetAnimationSettings(cosmetic);
			rebuildWidgets();
		}).bounds(width / 2 - 154, footerY, 100, 20).build());
		reset.active = !clips.isEmpty();
		addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.animations.advanced"), b -> minecraft.gui.setScreen(new PhysicsScreen(this, cosmetic)))
			.bounds(width / 2 - 50, footerY, 100, 20)
			.tooltip(Tooltip.create(Component.translatable("orbit_cosmetic.animations.advanced.tooltip")))
			.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 54, footerY, 100, 20).build());
	}

	private static String formatSeconds(double seconds) {
		return String.format(Locale.ROOT, seconds % 1 == 0 ? "%.0f" : "%.1f", seconds);
	}

	private void update(int index, AnimationSettings settings) {
		OrbitClient.get().library().setAnimationSettings(cosmetic, index, settings);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable("orbit_cosmetic.animations.subtitle").withStyle(ChatFormatting.GRAY), width / 2, 22, 0xFFFFFFFF);
		List<AnimationClip> clips = cosmetic.model() == null ? List.of() : cosmetic.model().animations();
		if (clips.isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.animations.none").withStyle(ChatFormatting.GRAY), width / 2, height / 2 - 10, 0xFFFFFFFF);
		}
		int perPage = rowsPerPage();
		int rowWidth = Math.min(420, width - 20);
		int left = (width - rowWidth) / 2;
		for (int row = 0; row < perPage; row++) {
			int index = page * perPage + row;
			if (index >= clips.size()) {
				break;
			}
			AnimationClip clip = clips.get(index);
			int y = 40 + row * ROW_HEIGHT;
			Component name = Component.literal(clip.name()).withStyle(ChatFormatting.BOLD)
				.append(Component.literal(String.format(Locale.ROOT, "  %.2fs", clip.length())).withStyle(ChatFormatting.DARK_GRAY));
			graphics.text(font, name, left, y + 1, 0xFFFFFFFF);
		}
		int pages = Math.max(1, (clips.size() + perPage - 1) / perPage);
		if (pages > 1) {
			int rowLeft = (width - Math.min(420, width - 20)) / 2;
			graphics.text(font, Component.literal((page + 1) + "/" + pages), rowLeft + 50, height - 22, 0xFFAAAAAA);
		}
	}
}

package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.anim.PhysicsSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edits how one part reacts to movement: for each of position X/Y/Z and rotation X/Y/Z (Blockbench axes),
 * what drives it, how strongly, up to which value, and how much it resists (smooths) changes.
 */
public final class PhysicsPartScreen extends Screen {
	private static final int TOP = 48;
	private static final int ROW_HEIGHT = 24;
	private static final int LABEL_WIDTH = 66;

	private final Screen parent;
	private final LocalCosmetic cosmetic;
	private final int bone;

	public PhysicsPartScreen(Screen parent, LocalCosmetic cosmetic, int bone) {
		super(Component.translatable("orbit_cosmetic.physics.part_title", PartTreeScreen.path(cosmetic.model(), bone)));
		this.parent = parent;
		this.cosmetic = cosmetic;
		this.bone = bone;
	}

	private List<AxisDrive> axes() {
		// PhysicsLink fills missing axes with OFF and clamps values.
		return new PhysicsLink(bone, cosmetic.physicsFor(bone)).axes();
	}

	@Override
	protected void init() {
		List<AxisDrive> axes = axes();
		if (cosmetic.physicsFor(bone) == null) {
			// Opening a part creates its entry so it shows up in the list even before any axis is set.
			OrbitClient.get().library().setPhysics(cosmetic, bone, axes);
		}
		int rowWidth = Math.min(440, width - 20);
		int left = (width - rowWidth) / 2;
		int column = (rowWidth - LABEL_WIDTH - 12) / 4;
		for (int axis = 0; axis < PhysicsLink.AXES; axis++) {
			int a = axis;
			boolean rotation = axis >= 3;
			AxisDrive drive = axes.get(axis);
			int y = TOP + axis * ROW_HEIGHT;
			int x = left + LABEL_WIDTH;
			addRenderableWidget(CycleButton.<PhysicsSource>builder(source -> Component.translatable("orbit_cosmetic.physics.source." + source.name().toLowerCase(Locale.ROOT)), drive.source())
				.withValues(PhysicsSource.values())
				.withTooltip(source -> Tooltip.create(Component.translatable("orbit_cosmetic.physics.source." + source.name().toLowerCase(Locale.ROOT) + ".tooltip")))
				.displayOnlyValue()
				.create(x, y, column, 20, Component.translatable("orbit_cosmetic.physics.source"), (button, value) -> update(a, current(a).withSource(value))));
			float strengthLimit = rotation ? AxisDrive.MAX_ROTATION_STRENGTH : AxisDrive.MAX_POSITION_STRENGTH;
			addRenderableWidget(new ValueSlider(x + column + 4, y, column, -strengthLimit, strengthLimit, rotation ? 0.5 : 0.1, drive.strength(),
				value -> Component.translatable("orbit_cosmetic.physics.strength", format(value)),
				value -> update(a, current(a).withStrength((float) value))));
			float maxLimit = rotation ? AxisDrive.MAX_ROTATION : AxisDrive.MAX_POSITION;
			addRenderableWidget(new ValueSlider(x + (column + 4) * 2, y, column, 0, maxLimit, rotation ? 1 : 0.25, drive.max(),
				value -> Component.translatable(rotation ? "orbit_cosmetic.physics.max_degrees" : "orbit_cosmetic.physics.max_pixels", format(value)),
				value -> update(a, current(a).withMax((float) value))));
			addRenderableWidget(new ValueSlider(x + (column + 4) * 3, y, column, 0, AxisDrive.MAX_RESISTANCE, 0.01, drive.resistance(),
				value -> Component.translatable("orbit_cosmetic.physics.resistance", String.format(Locale.ROOT, "%.2f", value)),
				value -> update(a, current(a).withResistance((float) value))));
		}
		int footerY = height - 28;
		addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.physics.remove_part"), b -> {
			OrbitClient.get().library().removePhysics(cosmetic, bone);
			onClose();
		}).bounds(width / 2 - 154, footerY, 150, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 4, footerY, 150, 20).build());
	}

	private AxisDrive current(int axis) {
		List<AxisDrive> saved = cosmetic.physicsFor(bone);
		return saved != null && axis < saved.size() ? saved.get(axis) : AxisDrive.OFF;
	}

	private void update(int axis, AxisDrive drive) {
		List<AxisDrive> axes = new ArrayList<>(PhysicsLink.AXES);
		for (int i = 0; i < PhysicsLink.AXES; i++) {
			axes.add(i == axis ? drive.clamped(i >= 3) : current(i));
		}
		OrbitClient.get().library().setPhysics(cosmetic, bone, axes);
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, Math.abs(value % 1) < 0.001 ? "%.0f" : "%.2f", value);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
		int rowWidth = Math.min(440, width - 20);
		int left = (width - rowWidth) / 2;
		int column = (rowWidth - LABEL_WIDTH - 12) / 4;
		int x = left + LABEL_WIDTH;
		String[] headers = {"orbit_cosmetic.physics.source", "orbit_cosmetic.physics.header.strength", "orbit_cosmetic.physics.header.max", "orbit_cosmetic.physics.header.resistance"};
		for (int i = 0; i < headers.length; i++) {
			graphics.centeredText(font, Component.translatable(headers[i]).withStyle(ChatFormatting.GRAY), x + (column + 4) * i + column / 2, TOP - 12, 0xFFFFFFFF);
		}
		for (int axis = 0; axis < PhysicsLink.AXES; axis++) {
			graphics.text(font, Component.translatable("orbit_cosmetic.physics.axis." + PhysicsLink.AXIS_NAMES[axis]), left, TOP + axis * ROW_HEIGHT + 6, 0xFFFFFFFF);
		}
		int y = TOP + PhysicsLink.AXES * ROW_HEIGHT + 6;
		// Only as many help lines as fit above the footer buttons.
		for (FormattedCharSequence line : font.split(Component.translatable("orbit_cosmetic.physics.help").withStyle(ChatFormatting.GRAY), rowWidth)) {
			if (y + 9 > height - 32) {
				break;
			}
			graphics.text(font, line, left, y, 0xFFFFFFFF);
			y += 10;
		}
	}
}

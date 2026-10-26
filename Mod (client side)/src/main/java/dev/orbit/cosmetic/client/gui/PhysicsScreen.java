package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.protocol.OrbitProtocol;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Advanced animation settings: the parts of a cosmetic that react to the wearer's movement. */
public final class PhysicsScreen extends Screen {
	private static final int ROW_HEIGHT = 24;
	private static final int TOP = 44;

	private final Screen parent;
	private final LocalCosmetic cosmetic;
	private int page;

	public PhysicsScreen(Screen parent, LocalCosmetic cosmetic) {
		super(Component.translatable("orbit_cosmetic.physics.title", cosmetic.displayName()));
		this.parent = parent;
		this.cosmetic = cosmetic;
	}

	private int rowsPerPage() {
		return Math.max(1, (height - TOP - 40) / ROW_HEIGHT);
	}

	private List<PhysicsLink> links() {
		return cosmetic.physicsLinks();
	}

	@Override
	protected void init() {
		List<PhysicsLink> links = links();
		int perPage = rowsPerPage();
		int pages = Math.max(1, (links.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		int rowWidth = Math.min(420, width - 20);
		int left = (width - rowWidth) / 2;
		for (int row = 0; row < perPage; row++) {
			int index = page * perPage + row;
			if (index >= links.size()) {
				break;
			}
			int bone = links.get(index).bone();
			int y = TOP + row * ROW_HEIGHT;
			addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.physics.edit"), b -> minecraft.gui.setScreen(new PhysicsPartScreen(this, cosmetic, bone)))
				.bounds(left + rowWidth - 124, y, 60, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.physics.remove"), b -> {
				OrbitClient.get().library().removePhysics(cosmetic, bone);
				rebuildWidgets();
			}).bounds(left + rowWidth - 60, y, 60, 20).build());
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
		Button add = addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.physics.add"), b ->
				minecraft.gui.setScreen(new PartTreeScreen(this, cosmetic, bone -> minecraft.gui.setScreen(new PhysicsPartScreen(this, cosmetic, bone)))))
			.bounds(width / 2 - 154, footerY, 150, 20).build());
		add.active = links.size() < OrbitProtocol.MAX_PHYSICS_LINKS;
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 4, footerY, 150, 20).build());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable("orbit_cosmetic.physics.subtitle", OrbitProtocol.MAX_PHYSICS_LINKS).withStyle(ChatFormatting.GRAY), width / 2, 24, 0xFFFFFFFF);
		List<PhysicsLink> links = links();
		if (links.isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.physics.empty").withStyle(ChatFormatting.GRAY), width / 2, height / 2 - 10, 0xFFFFFFFF);
			return;
		}
		int perPage = rowsPerPage();
		int rowWidth = Math.min(420, width - 20);
		int left = (width - rowWidth) / 2;
		for (int row = 0; row < perPage; row++) {
			int index = page * perPage + row;
			if (index >= links.size()) {
				break;
			}
			PhysicsLink link = links.get(index);
			int y = TOP + row * ROW_HEIGHT;
			graphics.text(font, Component.literal(PartTreeScreen.path(cosmetic.model(), link.bone())), left, y + 1, 0xFFFFFFFF);
			graphics.text(font, summary(link).withStyle(ChatFormatting.GRAY), left, y + 11, 0xFFFFFFFF);
		}
	}

	/** "Rotation X: Forward, Position Y: Vertical" or "No axes set". */
	static MutableComponent summary(PhysicsLink link) {
		List<Component> parts = new ArrayList<>();
		for (int axis = 0; axis < PhysicsLink.AXES; axis++) {
			AxisDrive drive = link.axes().get(axis);
			if (drive.active()) {
				parts.add(Component.translatable("orbit_cosmetic.physics.axis." + PhysicsLink.AXIS_NAMES[axis])
					.append(": ")
					.append(Component.translatable("orbit_cosmetic.physics.source." + drive.source().name().toLowerCase(Locale.ROOT))));
			}
		}
		if (parts.isEmpty()) {
			return Component.translatable("orbit_cosmetic.physics.no_axes");
		}
		MutableComponent result = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			result.append(i == 0 ? Component.empty() : Component.literal(", ")).append(parts.get(i));
		}
		return result;
	}
}

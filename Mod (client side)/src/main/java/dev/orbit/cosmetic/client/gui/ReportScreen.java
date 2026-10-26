package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.UUID;

/** Reports another player's cosmetics to the Orbit Cloud moderators. */
public final class ReportScreen extends Screen {
	/** Values understood by the backend (POST /v1/reports). */
	private static final List<String> REASONS = List.of("inappropriate", "offensive", "lag", "impersonation", "other");
	private static final int MAX_NOTE = 200;

	private final Screen parent;
	private final UUID player;
	private final String name;
	private final List<String> cosmetics;
	private String reason = REASONS.getFirst();
	private EditBox note;
	private Button send;
	private Component status;
	private boolean failed;

	public ReportScreen(Screen parent, UUID player, String name, Wearer wearer) {
		super(Component.translatable("orbit_cosmetic.report.title", name));
		this.parent = parent;
		this.player = player;
		this.name = name;
		this.cosmetics = wearer == null ? List.of() : wearer.worn().stream().map(worn -> worn.asset().hex()).toList();
	}

	@Override
	protected void init() {
		int left = width / 2 - 150;
		int y = height / 2 - 24;
		addRenderableWidget(CycleButton.<String>builder(value -> Component.translatable("orbit_cosmetic.report.reason." + value), reason)
			.withValues(REASONS)
			.create(left, y, 300, 20, Component.translatable("orbit_cosmetic.report.reason"), (button, value) -> reason = value));
		note = new EditBox(font, left, y + 28, 300, 20, Component.translatable("orbit_cosmetic.report.note"));
		note.setMaxLength(MAX_NOTE);
		note.setHint(Component.translatable("orbit_cosmetic.report.note").withStyle(EditBox.SEARCH_HINT_STYLE));
		addRenderableWidget(note);
		send = addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.report.send"), b -> sendReport())
			.bounds(left, y + 60, 148, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(left + 152, y + 60, 148, 20).build());
	}

	private void sendReport() {
		send.active = false;
		failed = false;
		status = Component.translatable("orbit_cosmetic.report.sending");
		OrbitClient.get().backend().report(player, name, reason, note.getValue().trim(), cosmetics,
			created -> {
				Notifications.success(Component.translatable("orbit_cosmetic.notify.report_sent", name),
					Component.translatable(created ? "orbit_cosmetic.notify.report_sent.detail" : "orbit_cosmetic.notify.report_updated"));
				onClose();
			},
			message -> {
				failed = true;
				status = Component.literal(message);
				send.active = true;
			});
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, height / 2 - 80, 0xFFFFFFFF);
		int y = height / 2 - 64;
		for (FormattedCharSequence line : font.split(Component.translatable("orbit_cosmetic.report.explain").withStyle(ChatFormatting.GRAY), 300)) {
			graphics.centeredText(font, line, width / 2, y, 0xFFFFFFFF);
			y += 10;
		}
		if (status != null) {
			int statusY = height / 2 + 64;
			for (FormattedCharSequence line : font.split(status.copy().withStyle(failed ? ChatFormatting.RED : ChatFormatting.GRAY), 300)) {
				graphics.centeredText(font, line, width / 2, statusY, 0xFFFFFFFF);
				statusY += 10;
			}
		}
	}
}

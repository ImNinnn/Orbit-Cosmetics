package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** An Orbit notification: the Orbit-red panel with the Orbit dot, a coloured title and up to three lines. */
public final class OrbitToast implements Toast {
	public enum Kind {
		INFO(0xFFFFFFFF),
		SUCCESS(0xFF7CE38B),
		WARNING(0xFFFFCC4D),
		ERROR(0xFFFF6E6E);

		final int color;

		Kind(int color) {
			this.color = color;
		}
	}

	private static final Identifier BACKGROUND = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "notification");
	private static final Identifier ICON = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "notification_icon");
	private static final long DISPLAY_MS = 5000;
	private static final int TEXT_LEFT = 30;
	private static final int MIN_WIDTH = 160;
	private static final int MAX_WIDTH = 240;
	private static final int MAX_LINES = 3;

	private final Kind kind;
	private final Component title;
	private final List<FormattedCharSequence> lines;
	private final int width;
	private Visibility wanted = Visibility.SHOW;

	public OrbitToast(Font font, Kind kind, Component title, Component message) {
		this.kind = kind;
		this.title = title;
		int textWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Math.max(font.width(title), message == null ? 0 : font.width(message)) + TEXT_LEFT + 8)) - TEXT_LEFT - 8;
		List<FormattedCharSequence> split = message == null ? List.of() : font.split(message, textWidth);
		this.lines = split.size() > MAX_LINES ? split.subList(0, MAX_LINES) : split;
		this.width = textWidth + TEXT_LEFT + 8;
	}

	@Override
	public Visibility getWantedVisibility() {
		return wanted;
	}

	@Override
	public void update(ToastManager manager, long visibleTime) {
		wanted = visibleTime >= DISPLAY_MS * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
	}

	@Override
	public int width() {
		return width;
	}

	@Override
	public int height() {
		return Math.max(32, 20 + lines.size() * 10);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long visibleTime) {
		int height = height();
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width, height);
		int iconY = (height - 16) / 2;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ICON, 3, iconY, 16, 16);
		if (kind != Kind.INFO) {
			// A small status pip on the dot: green, yellow or red.
			graphics.fill(14, iconY + 11, 18, iconY + 15, 0xFF0A0A0D);
			graphics.fill(15, iconY + 12, 17, iconY + 14, kind.color);
		}
		int y = lines.isEmpty() ? 12 : 7;
		graphics.text(font, title, TEXT_LEFT, y, kind.color, false);
		for (FormattedCharSequence line : lines) {
			y += 10;
			graphics.text(font, line, TEXT_LEFT, y, 0xFFC9C9D2, false);
		}
	}
}

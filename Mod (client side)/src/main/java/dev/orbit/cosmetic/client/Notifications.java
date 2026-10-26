package dev.orbit.cosmetic.client;

import dev.orbit.cosmetic.client.gui.OrbitToast;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Orbit's on-screen notifications. Safe to call from any thread. Every notification is also logged, and the
 * same notification is not repeated within a few seconds.
 */
public final class Notifications {
	private static final long REPEAT_AFTER_MS = 4000;
	private static final Map<String, Long> recent = new HashMap<>();

	private Notifications() {
	}

	public static void info(Component title, Component message) {
		show(OrbitToast.Kind.INFO, title, message);
	}

	public static void success(Component title, Component message) {
		show(OrbitToast.Kind.SUCCESS, title, message);
	}

	public static void warning(Component title, Component message) {
		show(OrbitToast.Kind.WARNING, title, message);
	}

	public static void error(Component title, Component message) {
		show(OrbitToast.Kind.ERROR, title, message);
	}

	public static void show(OrbitToast.Kind kind, Component title, Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			String text = title.getString() + (message == null ? "" : ": " + message.getString());
			long now = System.currentTimeMillis();
			Long last = recent.get(text);
			if (last != null && now - last < REPEAT_AFTER_MS) {
				return;
			}
			recent.values().removeIf(time -> now - time > REPEAT_AFTER_MS);
			recent.put(text, now);
			OrbitClient.LOGGER.info("[notification] {}", text);
			OrbitClient orbit = OrbitClient.get();
			if (orbit != null && !orbit.config().showNotifications) {
				return;
			}
			minecraft.gui.toastManager().addToast(new OrbitToast(minecraft.font, kind, title, message));
		});
	}
}

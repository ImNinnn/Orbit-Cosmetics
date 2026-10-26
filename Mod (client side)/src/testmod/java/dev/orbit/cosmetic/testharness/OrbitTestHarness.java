package dev.orbit.cosmetic.testharness;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import dev.orbit.cosmetic.client.gui.AnimationSettingsScreen;
import dev.orbit.cosmetic.client.gui.CosmeticManagerScreen;
import dev.orbit.cosmetic.client.gui.PartTreeScreen;
import dev.orbit.cosmetic.client.gui.PhysicsPartScreen;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.Locale;

/**
 * Development-only automation for the testClient run (never packaged). Set ORBIT_TEST_ROLE:
 * <ul>
 *   <li>{@code wearer}: screenshots its own cosmetics from the front, back and first person, then the manager</li>
 *   <li>{@code viewer}: waits until another player's cosmetics are loaded, screenshots them, and quits</li>
 *   <li>{@code features}: notifications, the Players menu, a report, the featured catalog and hiding a player</li>
 * </ul>
 */
public final class OrbitTestHarness implements ClientModInitializer {
	private static final String ROLE = System.getenv().getOrDefault("ORBIT_TEST_ROLE", "wearer").toLowerCase(Locale.ROOT);
	private static final int TIMEOUT = Integer.parseInt(System.getenv().getOrDefault("ORBIT_TEST_TIMEOUT_TICKS", "2400"));
	private int ticks;
	private int totalTicks;
	private int readyAt = -1;
	private java.util.List<dev.orbit.cosmetic.common.anim.AxisDrive> saved;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		OrbitClient.LOGGER.info("[harness] role={}", ROLE);
	}

	private void tick(Minecraft minecraft) {
		// Never leave a window behind: quit two minutes (by default) after the game started, whatever happened.
		if (++totalTicks == TIMEOUT) {
			OrbitClient.LOGGER.info("[harness] timeout, stopping");
			minecraft.stop();
		}
		if (minecraft.player == null || minecraft.level == null) {
			return;
		}
		ticks++;
		if (ROLE.equals("viewer")) {
			viewer(minecraft);
		} else if (ROLE.equals("physics")) {
			physics(minecraft);
		} else if (ROLE.equals("features")) {
			features(minecraft);
		} else {
			wearer(minecraft);
		}
	}

	private int bannedAt = -1;

	private void wearer(Minecraft minecraft) {
		if (bannedAt < 0 && OrbitClient.get().backend().isBanned()) {
			bannedAt = ticks;
			OrbitClient.LOGGER.info("[harness:wearer] banned from Orbit Cloud at tick {}", ticks);
		}
		if (bannedAt >= 0 && ticks == bannedAt + 15) {
			shot(minecraft, "wearer_banned");
		}
		switch (ticks) {
			case 160 -> {
				diagnostics("wearer");
				minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
			}
			case 200 -> shot(minecraft, "front");
			case 210 -> minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			case 250 -> shot(minecraft, "back");
			case 260 -> minecraft.options.setCameraType(CameraType.FIRST_PERSON);
			case 300 -> shot(minecraft, "first_person");
			case 310 -> minecraft.gui.setScreen(new CosmeticManagerScreen(null));
			case 350 -> shot(minecraft, "manager");
			default -> {
				// Stay connected for the viewer unless told to quit.
				if (ticks == TIMEOUT || ticks > 360 && System.getenv("ORBIT_TEST_QUIT") != null && ticks == 380) {
					diagnostics("wearer-final");
					minecraft.stop();
				}
				// Keep moving so the viewer sees movement physics: sprint forward, then walk back.
				if (ticks > 360 && minecraft.gui.screen() == null) {
					int phase = (ticks - 360) % 80;
					minecraft.options.keyUp.setDown(phase < 30);
					minecraft.options.keySprint.setDown(phase < 30);
					minecraft.options.keyDown.setDown(phase >= 40 && phase < 70);
				}
				if (ticks == 361) {
					minecraft.gui.setScreen(null);
				}
				// Change physics while connected, exactly as the physics screens do.
				if (ticks == 700 || ticks == 1000) {
					LocalCosmetic demo = OrbitClient.get().library().entries().stream().filter(e -> e.key().equals("orbit_demo.bbmodel")).findFirst().orElse(null);
					if (demo != null && demo.model() != null) {
						int backpack = demo.model().bones().stream().filter(b -> b.name().equals("backpack")).findFirst().orElseThrow().index();
						if (ticks == 700) {
							saved = demo.physicsFor(backpack);
							OrbitClient.get().library().removePhysics(demo, backpack);
							OrbitClient.LOGGER.info("[harness:wearer] removed backpack physics");
						} else if (saved != null) {
							OrbitClient.get().library().setPhysics(demo, backpack, saved);
							OrbitClient.LOGGER.info("[harness:wearer] added backpack physics back");
						}
					}
				}
			}
		}
	}

	/** Screenshots the new animation screens, then sprints to show movement physics. */
	private void physics(Minecraft minecraft) {
		LocalCosmetic demo = OrbitClient.get().library().entries().stream().filter(e -> e.key().equals("orbit_demo.bbmodel")).findFirst().orElse(null);
		if (demo == null || demo.model() == null) {
			return;
		}
		int backpack = demo.model().bones().stream().filter(b -> b.name().equals("backpack")).findFirst().orElseThrow().index();
		switch (ticks) {
			case 100 -> {
				diagnostics("physics");
				OrbitClient.LOGGER.info("[harness] equipped physics={} animations={}", demo.physicsLinks(), demo.animationSettings());
				minecraft.gui.setScreen(new AnimationSettingsScreen(null, demo));
			}
			case 125 -> shot(minecraft, "animations");
			case 130 -> minecraft.gui.setScreen(new PartTreeScreen(null, demo, bone -> { }));
			case 155 -> shot(minecraft, "part_tree");
			case 160 -> minecraft.gui.setScreen(new PhysicsPartScreen(null, demo, backpack));
			case 185 -> shot(minecraft, "physics_part");
			case 190 -> {
				minecraft.gui.setScreen(null);
				minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			}
			case 220 -> shot(minecraft, "standing");
			case 225 -> {
				minecraft.options.keyUp.setDown(true);
				minecraft.options.keySprint.setDown(true);
			}
			case 265 -> shot(minecraft, "running");
			case 270 -> {
				minecraft.options.keyUp.setDown(false);
				minecraft.options.keySprint.setDown(false);
			}
			case 330 -> shot(minecraft, "settled");
			case 340 -> {
				diagnostics("physics-final");
				minecraft.stop();
			}
			default -> {
			}
		}
	}

	private void viewer(Minecraft minecraft) {
		boolean ready = OrbitClient.get().session().remoteWearers().values().stream()
			.anyMatch(w -> !w.worn().isEmpty() && w.worn().stream().allMatch(worn -> OrbitClient.get().registry().get(worn.asset()) != null));
		if (ready && readyAt < 0) {
			readyAt = ticks;
			diagnostics("viewer-ready");
		}
		if (readyAt >= 0 && ticks % 10 == 0) {
			for (Wearer wearer : OrbitClient.get().session().remoteWearers().values()) {
				for (Wearer.Worn worn : wearer.worn()) {
					if (ticks % 40 == 0 && worn.asset().shortHex().startsWith("0739")) {
						OrbitClient.LOGGER.info("[harness:viewer] links now {}", worn.physicsLinks().size());
					}
					if (!worn.physicsLinks().isEmpty()) {
						float max = 0;
						for (int l = 0; l < worn.physicsLinks().size(); l++) {
							for (int a = 0; a < 6; a++) {
								max = Math.max(max, Math.abs(worn.physics().value(l, a)));
							}
						}
						OrbitClient.LOGGER.info("[harness:viewer] remote {} links={} strongest physics value={}", worn.asset().shortHex(), worn.physicsLinks().size(), max);
					}
				}
			}
		}
		if (readyAt >= 0 && ticks == readyAt + 300) {
			shot(minecraft, "viewer");
		}
		int stayFor = System.getenv("ORBIT_TEST_VIEWER_TICKS") != null ? Integer.parseInt(System.getenv("ORBIT_TEST_VIEWER_TICKS")) : 320;
		if (readyAt >= 0 && ticks == readyAt + stayFor || ticks == 2400) {
			diagnostics("viewer-final");
			minecraft.stop();
		}
	}

	private Wearer target;

	/** Walks through the client features of the Players menu, reports, the catalog and notifications. */
	private void features(Minecraft minecraft) {
		OrbitClient orbit = OrbitClient.get();
		if (ticks == 125) {
			shot(minecraft, "f_connected");
		}
		if (target == null) {
			target = orbit.session().remoteWearers().values().stream()
				.filter(w -> !w.worn().isEmpty() && w.worn().stream().allMatch(worn -> orbit.registry().get(worn.asset()) != null)).findFirst().orElse(null);
			if (target != null) {
				readyAt = ticks;
				diagnostics("features-ready");
			}
			return;
		}
		int t = ticks - readyAt;
		try {
			switch (t) {
				case 20 -> minecraft.gui.setScreen(new dev.orbit.cosmetic.client.gui.PlayersScreen(null));
				case 45 -> shot(minecraft, "f_players");
				case 50 -> {
					String name = minecraft.getConnection().getPlayerInfo(target.id).getProfile().name();
					minecraft.gui.setScreen(new dev.orbit.cosmetic.client.gui.ReportScreen(null, target.id, name, target));
				}
				case 70 -> shot(minecraft, "f_report");
				case 75 -> {
					var send = dev.orbit.cosmetic.client.gui.ReportScreen.class.getDeclaredMethod("sendReport");
					send.setAccessible(true);
					send.invoke(minecraft.gui.screen());
				}
				case 100 -> shot(minecraft, "f_report_sent");
				case 110 -> minecraft.gui.setScreen(new dev.orbit.cosmetic.client.gui.CatalogScreen(null));
				case 150 -> {
					var screen = minecraft.gui.screen();
					var items = dev.orbit.cosmetic.client.gui.CatalogScreen.class.getDeclaredField("items");
					items.setAccessible(true);
					@SuppressWarnings("unchecked")
					java.util.List<dev.orbit.cosmetic.client.net.FeaturedCosmetic> list = (java.util.List<dev.orbit.cosmetic.client.net.FeaturedCosmetic>) items.get(screen);
					OrbitClient.LOGGER.info("[harness:features] catalog {}", list);
					var sample = list.stream().filter(item -> item.name().toLowerCase(Locale.ROOT).contains("sample")).findFirst().orElseThrow();
					var select = dev.orbit.cosmetic.client.gui.CatalogScreen.class.getDeclaredMethod("select", dev.orbit.cosmetic.client.net.FeaturedCosmetic.class);
					select.setAccessible(true);
					select.invoke(screen, sample);
				}
				case 160 -> shot(minecraft, "f_catalog");
				case 165 -> {
					var get = dev.orbit.cosmetic.client.gui.CatalogScreen.class.getDeclaredMethod("getSelected");
					get.setAccessible(true);
					get.invoke(minecraft.gui.screen());
				}
				case 215 -> shot(minecraft, "f_catalog_got");
				case 220 -> {
					minecraft.gui.setScreen(null);
					java.nio.file.Files.writeString(orbit.paths().root().resolve("harness_broken.bbmodel"), "{ this is not json");
					orbit.library().reload();
				}
				case 245 -> shot(minecraft, "f_load_failed");
				case 250 -> {
					java.nio.file.Files.deleteIfExists(orbit.paths().root().resolve("harness_broken.bbmodel"));
					LocalCosmetic sample = orbit.library().entries().stream().filter(e -> e.key().startsWith("featured/")).findFirst().orElse(null);
					OrbitClient.LOGGER.info("[harness:features] featured entry {}", sample == null ? null : sample.key() + " loaded=" + sample.isLoaded());
					if (sample != null) {
						orbit.library().setLoaded(sample, false);
					}
				}
				case 270 -> shot(minecraft, "f_unloaded");
				case 280 -> {
					orbit.config().setHidden(target.id, true);
					minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				}
				case 310 -> shot(minecraft, "f_hidden");
				case 320 -> orbit.config().setHidden(target.id, false);
				case 350 -> shot(minecraft, "f_shown");
				default -> {
				}
			}
		} catch (ReflectiveOperationException | java.io.IOException e) {
			OrbitClient.LOGGER.error("[harness:features] step {} failed", t, e);
		}
		// After the external script banned the wearer, only featured cosmetics should remain on them.
		if (t > 360 && t % 100 == 0) {
			OrbitClient.LOGGER.info("[harness:features] wearer now wears {}", target.worn().stream().map(w -> w.asset().shortHex()).toList());
		}
		if (t == 1100) {
			shot(minecraft, "f_after_ban");
			diagnostics("features-final");
			minecraft.stop();
		}
	}

	private static void shot(Minecraft minecraft, String name) {
		Screenshot.grab(minecraft.gameDirectory, minecraft.gameRenderer.mainRenderTarget(), message -> OrbitClient.LOGGER.info("[harness] screenshot {}: {}", name, message.getString()));
	}

	private static void diagnostics(String label) {
		OrbitClient orbit = OrbitClient.get();
		OrbitClient.LOGGER.info("[harness:{}] orbitServer={} backendActive={} policy={}", label, orbit.session().isOrbitServer(), orbit.backend().isActive(), orbit.session().policy());
		for (LocalCosmetic entry : orbit.library().entries()) {
			OrbitClient.LOGGER.info("[harness:{}] local {} status={} loaded={} asset={} rejection={} issues={}", label, entry.key(), entry.status(), entry.isLoaded(),
				entry.asset() == null ? null : entry.asset().shortHex(), entry.serverRejection(), entry.issues());
		}
		for (Wearer wearer : orbit.session().remoteWearers().values()) {
			OrbitClient.LOGGER.info("[harness:{}] remote {} icon={} worn={}", label, wearer.id, wearer.showIcon,
				wearer.worn().stream().map(w -> w.asset().shortHex() + ":" + orbit.registry().state(w.asset())).toList());
		}
		OrbitClient.LOGGER.info("[harness:{}] baked cosmetics={}", label, orbit.registry().loadedCount());
	}
}

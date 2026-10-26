package dev.orbit.cosmetic.common.server;

import com.google.gson.JsonObject;
import dev.orbit.cosmetic.common.TestModels;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import dev.orbit.cosmetic.common.protocol.OrbitMessage;
import dev.orbit.cosmetic.common.protocol.OrbitMessage.*;
import dev.orbit.cosmetic.common.protocol.OrbitProtocol;
import dev.orbit.cosmetic.common.protocol.ProtocolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.orbit.cosmetic.common.TestModels.v;
import static org.junit.jupiter.api.Assertions.*;

class OrbitServerCoreTest {
	@TempDir
	Path data;

	private FakePlatform platform;
	private OrbitServerCore core;
	private long now = 1_000_000;

	private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private final UUID bob = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private final UUID vanilla = UUID.fromString("00000000-0000-0000-0000-00000000000c");

	final class FakePlatform implements ServerPlatform {
		final Map<UUID, List<OrbitMessage>> inbox = new HashMap<>();
		final Map<UUID, String> names = new HashMap<>(Map.of(alice, "Alice", bob, "Bob", vanilla, "Vanilla"));
		final Set<UUID> noPermission = new HashSet<>();
		final List<String> warnings = new ArrayList<>();

		@Override
		public void send(UUID player, byte[] payload) {
			try {
				inbox.computeIfAbsent(player, k -> new ArrayList<>()).add(OrbitProtocol.decodeClientbound(payload));
			} catch (ProtocolException e) {
				throw new AssertionError("server sent an undecodable message", e);
			}
		}

		@Override
		public boolean hasUsePermission(UUID player) {
			return !noPermission.contains(player);
		}

		@Override
		public String playerName(UUID player) {
			return names.get(player);
		}

		@Override
		public UUID findOnlinePlayer(String name) {
			return names.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name)).map(Map.Entry::getKey).findFirst().orElse(null);
		}

		@Override
		public Path dataDirectory() {
			return data;
		}

		@Override
		public void runOnMainThread(Runnable task) {
			task.run();
		}

		@Override
		public OrbitLogger logger() {
			return new OrbitLogger() {
				@Override
				public void info(String message) {
				}

				@Override
				public void warn(String message) {
					warnings.add(message);
				}

				@Override
				public void error(String message, Throwable error) {
					warnings.add(message);
				}
			};
		}

		<T extends OrbitMessage> List<T> received(UUID player, Class<T> type) {
			return inbox.getOrDefault(player, List.of()).stream().filter(type::isInstance).map(type::cast).toList();
		}

		void clear() {
			inbox.clear();
		}
	}

	@BeforeEach
	void setUp() {
		platform = new FakePlatform();
		core = new OrbitServerCore(platform, Runnable::run, () -> now);
	}

	private void send(UUID player, OrbitMessage message) {
		core.onPayload(player, OrbitProtocol.encode(message));
	}

	private void hello(UUID player) {
		send(player, new Hello(OrbitProtocol.VERSION, "test"));
	}

	private static byte[] hat() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject head = m.group("HEAD", 0, 24, 0);
		m.outliner.add(head);
		m.cubeIn(head, v(-4, 32, -4), v(4, 34, 4));
		return m.bytes();
	}

	private void upload(UUID player, byte[] asset) {
		AssetId id = AssetId.of(asset);
		List<byte[]> chunks = OrbitProtocol.split(asset, OrbitProtocol.UPLOAD_CHUNK_SIZE);
		for (int i = 0; i < chunks.size(); i++) {
			send(player, new UploadChunk(id, asset.length, i, chunks.size(), chunks.get(i)));
		}
	}

	private static EquippedCosmetic wear(byte[] asset) {
		return new EquippedCosmetic(AssetId.of(asset), "Hat", List.<AnimationSettings>of());
	}

	@Test
	void fullUploadAndDistributionFlow() {
		hello(alice);
		hello(bob);
		assertTrue(platform.received(alice, Welcome.class).getFirst().policy().canWear());

		byte[] asset = hat();
		AssetId id = AssetId.of(asset);
		send(alice, new Equip(List.of(wear(asset))));
		assertEquals(List.of(new UploadRequest(id)), platform.received(alice, UploadRequest.class));
		assertTrue(platform.received(bob, PlayerCosmetics.class).stream().allMatch(p -> p.cosmetics().isEmpty()), "nothing visible before upload");

		upload(alice, asset);
		assertTrue(platform.received(alice, UploadResult.class).getFirst().accepted());
		assertTrue(core.store().has(id));

		PlayerCosmetics seen = platform.received(bob, PlayerCosmetics.class).getLast();
		assertEquals(alice, seen.player());
		assertTrue(seen.showIcon());
		assertEquals(id, seen.cosmetics().getFirst().asset());

		platform.clear();
		send(bob, new RequestAsset(id));
		core.tick();
		ByteArrayOutputStream assembled = new ByteArrayOutputStream();
		for (AssetChunk chunk : platform.received(bob, AssetChunk.class)) {
			assembled.writeBytes(chunk.data());
		}
		assertArrayEquals(asset, assembled.toByteArray());

		// A third player joining later immediately learns about Alice's cosmetic, without re-uploading.
		UUID carol = UUID.randomUUID();
		hello(carol);
		assertEquals(id, platform.received(carol, PlayerCosmetics.class).stream()
			.filter(p -> p.player().equals(alice)).findFirst().orElseThrow().cosmetics().getFirst().asset());

		platform.clear();
		core.onDisconnect(alice);
		assertEquals(List.of(new PlayerRemove(alice)), platform.received(bob, PlayerRemove.class));
	}

	@Test
	void banningAWornCosmeticTellsTheWearer() {
		hello(alice);
		hello(bob);
		byte[] asset = hat();
		AssetId id = AssetId.of(asset);
		send(alice, new Equip(List.of(wear(asset))));
		upload(alice, asset);
		platform.clear();

		core.admin().execute(new String[] {"ban", id.hex().substring(0, 12)});
		UploadResult result = platform.received(alice, UploadResult.class).getFirst();
		assertEquals(id, result.asset());
		assertFalse(result.accepted());
		assertTrue(result.message().contains("banned"), result.message());
		assertTrue(platform.received(bob, UploadResult.class).isEmpty(), "only the wearer is told");
		assertTrue(platform.received(bob, PlayerCosmetics.class).getLast().cosmetics().isEmpty(), "and nobody sees it any more");
	}

	@Test
	void vanillaPlayersAreNeverSentAnything() {
		hello(alice);
		send(alice, new Equip(List.of(wear(hat()))));
		upload(alice, hat());
		core.tick();
		assertFalse(platform.inbox.containsKey(vanilla));
	}

	@Test
	void corruptedOrUnrequestedUploadsAreRejected() {
		hello(alice);
		byte[] asset = hat();
		upload(alice, asset);
		assertFalse(core.store().has(AssetId.of(asset)), "uploads the server did not ask for are ignored");

		send(alice, new Equip(List.of(wear(asset))));
		byte[] tampered = asset.clone();
		tampered[tampered.length - 2] ^= 1;
		AssetId id = AssetId.of(asset);
		List<byte[]> chunks = OrbitProtocol.split(tampered, OrbitProtocol.UPLOAD_CHUNK_SIZE);
		for (int i = 0; i < chunks.size(); i++) {
			send(alice, new UploadChunk(id, tampered.length, i, chunks.size(), chunks.get(i)));
		}
		assertFalse(platform.received(alice, UploadResult.class).getLast().accepted());
		assertFalse(core.store().has(id));
		assertFalse(core.securityLog().recent(10).isEmpty());
	}

	@Test
	void invalidModelsAreRejectedWithAReason() {
		hello(alice);
		byte[] notAModel = "{\"hello\": true}".getBytes();
		send(alice, new Equip(List.of(wear(notAModel))));
		upload(alice, notAModel);
		UploadResult result = platform.received(alice, UploadResult.class).getLast();
		assertFalse(result.accepted());
		assertTrue(result.message().startsWith("Invalid cosmetic"), result.message());

		platform.clear();
		send(alice, new Equip(List.of(wear(notAModel))));
		assertTrue(platform.received(alice, UploadRequest.class).isEmpty(), "known-bad assets are not requested again");
		assertFalse(platform.received(alice, UploadResult.class).getLast().accepted());
	}

	@Test
	void blockedPlayersCannotWearAndCannotBypass() {
		hello(alice);
		hello(bob);
		byte[] asset = hat();
		send(alice, new Equip(List.of(wear(asset))));
		upload(alice, asset);
		assertFalse(platform.received(bob, PlayerCosmetics.class).getLast().cosmetics().isEmpty());

		List<String> output = core.admin().execute(new String[] {"block", "alice"});
		assertTrue(output.getFirst().startsWith("Blocked Alice"), output.toString());
		assertFalse(platform.received(alice, PolicyUpdate.class).getLast().policy().canWear());
		PlayerCosmetics hidden = platform.received(bob, PlayerCosmetics.class).getLast();
		assertTrue(hidden.cosmetics().isEmpty());
		assertFalse(hidden.showIcon());

		// The client ignoring its policy and equipping anyway changes nothing.
		platform.clear();
		send(alice, new Equip(List.of(wear(asset))));
		assertTrue(platform.received(bob, PlayerCosmetics.class).isEmpty());
		assertEquals(1, platform.received(alice, Notice.class).size());

		// Reconnecting does not lift the block.
		core.onDisconnect(alice);
		hello(alice);
		assertFalse(platform.received(alice, Welcome.class).getLast().policy().canWear());

		core.admin().execute(new String[] {"unblock", alice.toString()});
		assertTrue(platform.received(alice, PolicyUpdate.class).getLast().policy().canWear());
	}

	@Test
	void permissionIsChecked() {
		platform.noPermission.add(alice);
		hello(alice);
		assertFalse(platform.received(alice, Welcome.class).getFirst().policy().canWear());
	}

	@Test
	void disablingHidesEverythingAndEnablingRestores() {
		hello(alice);
		hello(bob);
		byte[] asset = hat();
		send(alice, new Equip(List.of(wear(asset))));
		upload(alice, asset);

		platform.clear();
		core.admin().execute(new String[] {"disable"});
		assertFalse(platform.received(bob, PolicyUpdate.class).getLast().policy().enabled());
		send(bob, new RequestAsset(AssetId.of(asset)));
		core.tick();
		assertTrue(platform.received(bob, AssetChunk.class).isEmpty());

		platform.clear();
		core.admin().execute(new String[] {"enable"});
		assertTrue(platform.received(bob, PolicyUpdate.class).getLast().policy().enabled());
		assertFalse(platform.received(bob, PlayerCosmetics.class).getLast().cosmetics().isEmpty(), "state is re-sent after enabling");
	}

	@Test
	void approvalRequiredFlow() {
		core.config().requireApprovedAssets = true;
		core.applyConfigChange(false);
		hello(alice);
		hello(bob);
		byte[] asset = hat();
		AssetId id = AssetId.of(asset);
		send(alice, new Equip(List.of(wear(asset))));
		upload(alice, asset);
		UploadResult result = platform.received(alice, UploadResult.class).getLast();
		assertTrue(result.accepted());
		assertTrue(result.message().contains("approve"));
		assertTrue(platform.received(bob, PlayerCosmetics.class).getLast().cosmetics().isEmpty());

		List<String> output = core.admin().execute(new String[] {"approve", id.hex().substring(0, 10)});
		assertTrue(output.getFirst().startsWith("Approved"), output.toString());
		assertEquals(id, platform.received(bob, PlayerCosmetics.class).getLast().cosmetics().getFirst().asset());
	}

	@Test
	void disallowedAnchorsAreRejected() {
		core.config().allowedAnchors = new ArrayList<>(List.of("BODY"));
		core.applyConfigChange(true);
		hello(alice);
		byte[] asset = hat();
		send(alice, new Equip(List.of(wear(asset))));
		upload(alice, asset);
		UploadResult result = platform.received(alice, UploadResult.class).getLast();
		assertFalse(result.accepted());
		assertTrue(result.message().contains("HEAD"), result.message());
	}

	@Test
	void incompatibleProtocolGetsAnExplanation() {
		send(alice, new Hello(99, "future"));
		Welcome welcome = platform.received(alice, Welcome.class).getFirst();
		assertFalse(welcome.policy().enabled());
		assertTrue(welcome.policy().message().contains("protocol"));
	}

	@Test
	void garbageTrafficIsContainedAndEventuallyIgnored() {
		hello(alice);
		hello(bob);
		for (int i = 0; i < 500; i++) {
			core.onPayload(alice, new byte[] {(byte) 0x02, (byte) 0xFF, 1, 2, 3});
			now += 30;
		}
		assertFalse(core.securityLog().recent(100).isEmpty());
		// Once ignored, even valid messages from the offender are dropped until reconnect.
		platform.clear();
		send(alice, new Equip(List.of(wear(hat()))));
		assertTrue(platform.received(alice, UploadRequest.class).isEmpty());
		// Other players are unaffected.
		send(bob, new Equip(List.of(wear(hat()))));
		assertFalse(platform.received(bob, UploadRequest.class).isEmpty());
	}

	@Test
	void requestsForUnknownAssetsAreRefused() {
		hello(bob);
		AssetId random = AssetId.of("never uploaded".getBytes());
		send(bob, new RequestAsset(random));
		assertEquals(random, platform.received(bob, AssetUnavailable.class).getFirst().asset());
	}

	@Test
	void physicsLinksToMissingPartsAreDropped() {
		hello(alice);
		hello(bob);
		byte[] asset = hat();
		dev.orbit.cosmetic.common.anim.AxisDrive swing = new dev.orbit.cosmetic.common.anim.AxisDrive(dev.orbit.cosmetic.common.anim.PhysicsSource.SPEED, 5, 30, 0.5f);
		List<dev.orbit.cosmetic.common.anim.PhysicsLink> links = List.of(
			dev.orbit.cosmetic.common.anim.PhysicsLink.empty(0).withAxis(3, swing),
			dev.orbit.cosmetic.common.anim.PhysicsLink.empty(500).withAxis(3, swing));
		send(alice, new Equip(List.of(new EquippedCosmetic(AssetId.of(asset), "Hat", List.of(), links))));
		upload(alice, asset);
		List<dev.orbit.cosmetic.common.anim.PhysicsLink> seen = platform.received(bob, PlayerCosmetics.class).getLast().cosmetics().getFirst().physics();
		assertEquals(1, seen.size());
		assertEquals(0, seen.getFirst().bone());

		core.admin().execute(new String[] {"set", "animations", "off"});
		assertTrue(platform.received(bob, PlayerCosmetics.class).getLast().cosmetics().getFirst().physics().isEmpty(), "physics counts as animation");
	}

	@Test
	void stateFlagsAreRelayed() {
		hello(alice);
		hello(bob);
		send(alice, new State(1));
		assertEquals(new PlayerState(alice, 1), platform.received(bob, PlayerState.class).getLast());
	}

	@Test
	void adminSettingsPersist() {
		core.admin().execute(new String[] {"set", "maxEquipped", "2"});
		core.admin().execute(new String[] {"set", "uploads", "off"});
		ServerConfig reloaded = ServerConfig.load(data.resolve("orbit-server.json"), platform.logger());
		assertEquals(2, reloaded.maxEquippedPerPlayer);
		assertFalse(reloaded.allowUploads);
		assertFalse(core.admin().execute(new String[] {"status"}).isEmpty());
		assertTrue(core.admin().complete(new String[] {"bl"}).contains("block"));
	}
}

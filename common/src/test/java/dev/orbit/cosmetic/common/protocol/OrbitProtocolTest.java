package dev.orbit.cosmetic.common.protocol;

import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AnimationTrigger;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.anim.PhysicsSource;
import dev.orbit.cosmetic.common.anim.LoopMode;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.protocol.OrbitMessage.*;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrbitProtocolTest {
	private static final AssetId ASSET = AssetId.of("hello".getBytes());
	private static final UUID PLAYER = UUID.fromString("12345678-1234-1234-1234-123456789abc");

	private static EquippedCosmetic cosmetic() {
		return new EquippedCosmetic(ASSET, "Top hat", List.of(
			new AnimationSettings(AnimationTrigger.SPRINT, LoopMode.LOOP, 1.5f, 30, false),
			new AnimationSettings(AnimationTrigger.JUMP, LoopMode.ONCE, 1, 70, true),
			new AnimationSettings(AnimationTrigger.RANDOM, LoopMode.ONCE, 1, 15, false, 2.5f, 7.5f)
		), List.of(PhysicsLink.empty(3).withAxis(3, new AxisDrive(PhysicsSource.FORWARD, 12.5f, 60, 0.75f))));
	}

	@Test
	void serverboundRoundTrip() throws Exception {
		byte[] chunk = new byte[10];
		List<OrbitMessage> messages = List.of(
			new Hello(OrbitProtocol.VERSION, "1.0.0"),
			new Equip(List.of(cosmetic())),
			new Equip(List.of()),
			new UploadChunk(ASSET, 10, 0, 1, chunk),
			new RequestAsset(ASSET),
			new State(PlayerFlags.FLYING)
		);
		for (OrbitMessage message : messages) {
			OrbitMessage decoded = OrbitProtocol.decodeServerbound(OrbitProtocol.encode(message));
			if (message instanceof UploadChunk original) {
				UploadChunk copy = (UploadChunk) decoded;
				assertEquals(original.asset(), copy.asset());
				assertArrayEquals(original.data(), copy.data());
			} else {
				assertEquals(message, decoded);
			}
			assertThrows(ProtocolException.class, () -> OrbitProtocol.decodeClientbound(OrbitProtocol.encode(message)),
				"serverbound messages must not be accepted by the client");
		}
	}

	@Test
	void clientboundRoundTrip() throws Exception {
		ServerPolicy policy = new ServerPolicy(true, true, true, false, true, true, 4, EnumSet.of(Anchor.HEAD, Anchor.ROOT), ModelLimits.DEFAULT, "hi");
		List<OrbitMessage> messages = List.of(
			new Welcome(OrbitProtocol.VERSION, policy),
			new PolicyUpdate(ServerPolicy.disabled("off")),
			new PlayerCosmetics(PLAYER, true, List.of(cosmetic())),
			new PlayerRemove(PLAYER),
			new AssetUnavailable(ASSET, "nope"),
			new UploadRequest(ASSET),
			new UploadResult(ASSET, false, "too big"),
			new PlayerState(PLAYER, PlayerFlags.FLYING),
			new Notice(Notice.WARNING, "careful")
		);
		for (OrbitMessage message : messages) {
			assertEquals(message, OrbitProtocol.decodeClientbound(OrbitProtocol.encode(message)));
			assertThrows(ProtocolException.class, () -> OrbitProtocol.decodeServerbound(OrbitProtocol.encode(message)));
		}
	}

	@Test
	void chunkNumberingIsValidated() {
		byte[] wrongLength = OrbitProtocol.encode(new UploadChunk(ASSET, 100, 0, 1, new byte[50]));
		assertThrows(ProtocolException.class, () -> OrbitProtocol.decodeServerbound(wrongLength));
		byte[] wrongCount = OrbitProtocol.encode(new UploadChunk(ASSET, 100, 0, 2, new byte[100]));
		assertThrows(ProtocolException.class, () -> OrbitProtocol.decodeServerbound(wrongCount));
		byte[] huge = OrbitProtocol.encode(new UploadChunk(ASSET, Integer.MAX_VALUE, 0, 1, new byte[1]));
		assertThrows(ProtocolException.class, () -> OrbitProtocol.decodeServerbound(huge));
	}

	@Test
	void truncatedAndTrailingBytesAreRejected() {
		byte[] full = OrbitProtocol.encode(new Equip(List.of(cosmetic())));
		for (int length = 0; length < full.length; length++) {
			byte[] truncated = java.util.Arrays.copyOf(full, length);
			assertThrows(ProtocolException.class, () -> OrbitProtocol.decodeServerbound(truncated));
		}
		byte[] trailing = java.util.Arrays.copyOf(full, full.length + 1);
		assertThrows(ProtocolException.class, () -> OrbitProtocol.decodeServerbound(trailing));
	}

	@Test
	void randomBytesOnlyEverCauseProtocolExceptions() {
		Random random = new Random(7);
		for (int i = 0; i < 20000; i++) {
			byte[] payload = new byte[random.nextInt(200)];
			random.nextBytes(payload);
			if (payload.length > 0 && random.nextBoolean()) {
				payload[0] = (byte) (random.nextBoolean() ? 0x02 : 0x83);
			}
			try {
				OrbitProtocol.decodeServerbound(payload);
			} catch (ProtocolException expected) {
				// fine
			}
			try {
				OrbitProtocol.decodeClientbound(payload);
			} catch (ProtocolException expected) {
				// fine
			}
		}
	}

	@Test
	void chunkAssemblerRejectsDuplicates() {
		byte[] asset = new byte[OrbitProtocol.UPLOAD_CHUNK_SIZE + 5];
		new Random(1).nextBytes(asset);
		List<byte[]> chunks = OrbitProtocol.split(asset, OrbitProtocol.UPLOAD_CHUNK_SIZE);
		assertEquals(2, chunks.size());
		AssetId id = AssetId.of(asset);
		ChunkAssembler assembler = new ChunkAssembler(id, asset.length, 2, OrbitProtocol.UPLOAD_CHUNK_SIZE, 0);
		assertTrue(assembler.accept(1, 2, asset.length, chunks.get(1), 0));
		assertFalse(assembler.accept(1, 2, asset.length, chunks.get(1), 0));
		assertFalse(assembler.isComplete());
		assertTrue(assembler.accept(0, 2, asset.length, chunks.get(0), 0));
		assertTrue(assembler.isComplete());
		assertTrue(id.matches(assembler.bytes()));
	}
}

package dev.orbit.cosmetic.common.protocol;

import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AnimationTrigger;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.LoopMode;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.anim.PhysicsSource;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.protocol.OrbitMessage.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Wire format of the Orbit plugin channel. Payloads are raw bytes (no Minecraft framing) so the Fabric and
 * Paper servers share one implementation: {@code [message id: u8][fields...]}, big-endian.
 * See docs/PROTOCOL.md.
 */
public final class OrbitProtocol {
	public static final String NAMESPACE = "orbit_cosmetic";
	public static final String CHANNEL_PATH = "sync";
	public static final String CHANNEL = NAMESPACE + ":" + CHANNEL_PATH;
	/** 2: random animation wait times and movement physics. */
	public static final int VERSION = 2;

	/** Vanilla caps serverbound custom payloads at 32767 bytes; chunks stay well below it. */
	public static final int MAX_SERVERBOUND_PAYLOAD = 32000;
	/** Vanilla and Bukkit cap clientbound custom payloads at 1 MiB. */
	public static final int MAX_CLIENTBOUND_PAYLOAD = 1024 * 1024;
	public static final int UPLOAD_CHUNK_SIZE = 24 * 1024;
	public static final int DOWNLOAD_CHUNK_SIZE = 96 * 1024;
	/** Hard cap on cosmetics per player, whatever the server configures. */
	public static final int MAX_EQUIPPED = 16;
	public static final int MAX_ANIMATIONS = 64;
	public static final int MAX_PHYSICS_LINKS = 8;
	public static final int MAX_CHUNKS = 1024;
	public static final int MAX_MESSAGE_CHARS = 256;
	public static final int MAX_NAME_CHARS = 64;

	private static final int HELLO = 0x01;
	private static final int EQUIP = 0x02;
	private static final int UPLOAD_CHUNK = 0x03;
	private static final int REQUEST_ASSET = 0x04;
	private static final int STATE = 0x05;

	private static final int WELCOME = 0x81;
	private static final int POLICY = 0x82;
	private static final int PLAYER_COSMETICS = 0x83;
	private static final int PLAYER_REMOVE = 0x84;
	private static final int ASSET_CHUNK = 0x85;
	private static final int ASSET_UNAVAILABLE = 0x86;
	private static final int UPLOAD_REQUEST = 0x87;
	private static final int UPLOAD_RESULT = 0x88;
	private static final int PLAYER_STATE = 0x89;
	private static final int NOTICE = 0x8A;

	private OrbitProtocol() {
	}

	public static byte[] encode(OrbitMessage message) {
		ByteWriter out = new ByteWriter(64);
		switch (message) {
			case Hello m -> {
				out.writeByte(HELLO).writeShort(m.protocolVersion()).writeString(m.modVersion(), 32);
			}
			case Equip m -> {
				out.writeByte(EQUIP);
				writeCosmetics(out, m.cosmetics());
			}
			case UploadChunk m -> {
				out.writeByte(UPLOAD_CHUNK);
				writeChunk(out, m.asset(), m.totalSize(), m.index(), m.count(), m.data());
			}
			case RequestAsset m -> out.writeByte(REQUEST_ASSET).writeFixed(m.asset().bytes());
			case State m -> out.writeByte(STATE).writeByte(m.flags());
			case Welcome m -> {
				out.writeByte(WELCOME).writeShort(m.protocolVersion());
				writePolicy(out, m.policy());
			}
			case PolicyUpdate m -> {
				out.writeByte(POLICY);
				writePolicy(out, m.policy());
			}
			case PlayerCosmetics m -> {
				out.writeByte(PLAYER_COSMETICS).writeUuid(m.player()).writeBoolean(m.showIcon());
				writeCosmetics(out, m.cosmetics());
			}
			case PlayerRemove m -> out.writeByte(PLAYER_REMOVE).writeUuid(m.player());
			case AssetChunk m -> {
				out.writeByte(ASSET_CHUNK);
				writeChunk(out, m.asset(), m.totalSize(), m.index(), m.count(), m.data());
			}
			case AssetUnavailable m -> out.writeByte(ASSET_UNAVAILABLE).writeFixed(m.asset().bytes()).writeString(m.reason(), MAX_MESSAGE_CHARS);
			case UploadRequest m -> out.writeByte(UPLOAD_REQUEST).writeFixed(m.asset().bytes());
			case UploadResult m -> out.writeByte(UPLOAD_RESULT).writeFixed(m.asset().bytes()).writeBoolean(m.accepted()).writeString(m.message(), MAX_MESSAGE_CHARS);
			case PlayerState m -> out.writeByte(PLAYER_STATE).writeUuid(m.player()).writeByte(m.flags());
			case Notice m -> out.writeByte(NOTICE).writeByte(m.level()).writeString(m.message(), MAX_MESSAGE_CHARS);
		}
		return out.toByteArray();
	}

	/** Decodes a message sent by a client. Clientbound message ids are rejected. */
	public static OrbitMessage decodeServerbound(byte[] payload) throws ProtocolException {
		if (payload.length == 0 || payload.length > MAX_SERVERBOUND_PAYLOAD) {
			throw new ProtocolException("Bad payload size " + payload.length);
		}
		ByteReader in = new ByteReader(payload);
		int id = in.readUnsignedByte();
		OrbitMessage message = switch (id) {
			case HELLO -> new Hello(in.readUnsignedShort(), in.readString(32));
			case EQUIP -> new Equip(readCosmetics(in));
			case UPLOAD_CHUNK -> readUploadChunk(in);
			case REQUEST_ASSET -> new RequestAsset(readAsset(in));
			case STATE -> new State(in.readUnsignedByte() & PlayerFlags.KNOWN);
			default -> throw new ProtocolException("Unknown serverbound message 0x" + Integer.toHexString(id));
		};
		in.expectEnd();
		return message;
	}

	/** Decodes a message sent by a server. Serverbound message ids are rejected. */
	public static OrbitMessage decodeClientbound(byte[] payload) throws ProtocolException {
		if (payload.length == 0 || payload.length > MAX_CLIENTBOUND_PAYLOAD) {
			throw new ProtocolException("Bad payload size " + payload.length);
		}
		ByteReader in = new ByteReader(payload);
		int id = in.readUnsignedByte();
		OrbitMessage message = switch (id) {
			case WELCOME -> new Welcome(in.readUnsignedShort(), readPolicy(in));
			case POLICY -> new PolicyUpdate(readPolicy(in));
			case PLAYER_COSMETICS -> new PlayerCosmetics(in.readUuid(), in.readBoolean(), readCosmetics(in));
			case PLAYER_REMOVE -> new PlayerRemove(in.readUuid());
			case ASSET_CHUNK -> readAssetChunk(in);
			case ASSET_UNAVAILABLE -> new AssetUnavailable(readAsset(in), in.readString(MAX_MESSAGE_CHARS));
			case UPLOAD_REQUEST -> new UploadRequest(readAsset(in));
			case UPLOAD_RESULT -> new UploadResult(readAsset(in), in.readBoolean(), in.readString(MAX_MESSAGE_CHARS));
			case PLAYER_STATE -> new PlayerState(in.readUuid(), in.readUnsignedByte() & PlayerFlags.KNOWN);
			case NOTICE -> new Notice(Math.min(in.readUnsignedByte(), Notice.ERROR), in.readString(MAX_MESSAGE_CHARS));
			default -> throw new ProtocolException("Unknown clientbound message 0x" + Integer.toHexString(id));
		};
		in.expectEnd();
		return message;
	}

	// ---------------------------------------------------------------- fields

	private static void writeChunk(ByteWriter out, AssetId asset, int totalSize, int index, int count, byte[] data) {
		out.writeFixed(asset.bytes()).writeInt(totalSize).writeShort(index).writeShort(count).writeBlob(data, 0, data.length);
	}

	private static UploadChunk readUploadChunk(ByteReader in) throws ProtocolException {
		AssetId asset = readAsset(in);
		int totalSize = in.readInt();
		int index = in.readUnsignedShort();
		int count = in.readUnsignedShort();
		byte[] data = in.readBlob(UPLOAD_CHUNK_SIZE);
		validateChunk(totalSize, index, count, data, UPLOAD_CHUNK_SIZE);
		return new UploadChunk(asset, totalSize, index, count, data);
	}

	private static AssetChunk readAssetChunk(ByteReader in) throws ProtocolException {
		AssetId asset = readAsset(in);
		int totalSize = in.readInt();
		int index = in.readUnsignedShort();
		int count = in.readUnsignedShort();
		byte[] data = in.readBlob(DOWNLOAD_CHUNK_SIZE);
		validateChunk(totalSize, index, count, data, DOWNLOAD_CHUNK_SIZE);
		return new AssetChunk(asset, totalSize, index, count, data);
	}

	private static void validateChunk(int totalSize, int index, int count, byte[] data, int chunkSize) throws ProtocolException {
		if (totalSize <= 0 || totalSize > ModelLimits.HARD.maxFileBytes()) {
			throw new ProtocolException("Bad asset size");
		}
		int expectedCount = (totalSize + chunkSize - 1) / chunkSize;
		if (count != expectedCount || count > MAX_CHUNKS || index >= count) {
			throw new ProtocolException("Bad chunk numbering");
		}
		int expectedLength = index == count - 1 ? totalSize - chunkSize * (count - 1) : chunkSize;
		if (data.length != expectedLength) {
			throw new ProtocolException("Bad chunk length");
		}
	}

	private static AssetId readAsset(ByteReader in) throws ProtocolException {
		return AssetId.fromBytes(in.readFixed(32));
	}

	private static void writeCosmetics(ByteWriter out, List<EquippedCosmetic> cosmetics) {
		int count = Math.min(cosmetics.size(), MAX_EQUIPPED);
		out.writeByte(count);
		for (int i = 0; i < count; i++) {
			EquippedCosmetic cosmetic = cosmetics.get(i);
			out.writeFixed(cosmetic.asset().bytes());
			out.writeString(cosmetic.name(), MAX_NAME_CHARS);
			int animations = Math.min(cosmetic.animations().size(), MAX_ANIMATIONS);
			out.writeByte(animations);
			for (int a = 0; a < animations; a++) {
				AnimationSettings settings = cosmetic.animations().get(a);
				out.writeByte(settings.trigger().ordinal());
				out.writeByte(settings.loop().ordinal());
				out.writeShort(Math.round(settings.speed() * 100));
				out.writeByte(settings.priority());
				out.writeBoolean(settings.interrupt());
				out.writeShort(Math.round(settings.minWait() * 10));
				out.writeShort(Math.round(settings.maxWait() * 10));
			}
			int links = Math.min(cosmetic.physics().size(), MAX_PHYSICS_LINKS);
			out.writeByte(links);
			for (int l = 0; l < links; l++) {
				PhysicsLink link = cosmetic.physics().get(l);
				out.writeShort(link.bone());
				for (AxisDrive drive : link.axes()) {
					out.writeByte(drive.source().ordinal());
					out.writeInt(Float.floatToIntBits(drive.strength()));
					out.writeInt(Float.floatToIntBits(drive.max()));
					out.writeInt(Float.floatToIntBits(drive.resistance()));
				}
			}
		}
	}

	private static List<EquippedCosmetic> readCosmetics(ByteReader in) throws ProtocolException {
		int count = in.readUnsignedByte();
		if (count > MAX_EQUIPPED) {
			throw new ProtocolException("Too many cosmetics");
		}
		List<EquippedCosmetic> result = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			AssetId asset = readAsset(in);
			String name = in.readString(MAX_NAME_CHARS);
			int animations = in.readUnsignedByte();
			if (animations > MAX_ANIMATIONS) {
				throw new ProtocolException("Too many animations");
			}
			List<AnimationSettings> settings = new ArrayList<>(animations);
			for (int a = 0; a < animations; a++) {
				int trigger = in.readUnsignedByte();
				int loop = in.readUnsignedByte();
				int speed = in.readUnsignedShort();
				int priority = in.readUnsignedByte();
				boolean interrupt = in.readBoolean();
				int minWait = in.readUnsignedShort();
				int maxWait = in.readUnsignedShort();
				if (trigger >= AnimationTrigger.values().length || loop >= LoopMode.values().length) {
					throw new ProtocolException("Unknown animation setting");
				}
				settings.add(new AnimationSettings(AnimationTrigger.byOrdinal(trigger), LoopMode.byOrdinal(loop), speed / 100f, priority, interrupt,
					minWait / 10f, maxWait / 10f));
			}
			int links = in.readUnsignedByte();
			if (links > MAX_PHYSICS_LINKS) {
				throw new ProtocolException("Too many physics links");
			}
			List<PhysicsLink> physics = new ArrayList<>(links);
			for (int l = 0; l < links; l++) {
				int bone = in.readUnsignedShort();
				List<AxisDrive> axes = new ArrayList<>(PhysicsLink.AXES);
				for (int a = 0; a < PhysicsLink.AXES; a++) {
					int source = in.readUnsignedByte();
					if (source >= PhysicsSource.values().length) {
						throw new ProtocolException("Unknown physics source");
					}
					axes.add(new AxisDrive(PhysicsSource.byOrdinal(source), Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt())));
				}
				physics.add(new PhysicsLink(bone, axes));
			}
			result.add(new EquippedCosmetic(asset, name, settings, physics));
		}
		return result;
	}

	private static void writePolicy(ByteWriter out, ServerPolicy policy) {
		int flags = (policy.enabled() ? 1 : 0)
			| (policy.canWear() ? 2 : 0)
			| (policy.uploadsAllowed() ? 4 : 0)
			| (policy.downloadsAllowed() ? 8 : 0)
			| (policy.showNameplateIcons() ? 16 : 0)
			| (policy.animationsAllowed() ? 32 : 0);
		out.writeByte(flags);
		out.writeByte(Math.min(policy.maxEquipped(), MAX_EQUIPPED));
		int anchors = 0;
		for (Anchor anchor : policy.allowedAnchors()) {
			anchors |= 1 << anchor.ordinal();
		}
		out.writeByte(anchors);
		ModelLimits l = policy.limits();
		for (int value : new int[] {l.maxFileBytes(), l.maxElements(), l.maxBones(), l.maxDepth(), l.maxQuads(), l.maxTextures(),
			l.maxTextureSize(), l.maxTotalTextureBytes(), l.maxAnimations(), l.maxKeyframes(), l.maxExpressionLength()}) {
			out.writeInt(value);
		}
		out.writeString(policy.message(), MAX_MESSAGE_CHARS);
	}

	private static ServerPolicy readPolicy(ByteReader in) throws ProtocolException {
		int flags = in.readUnsignedByte();
		int maxEquipped = Math.min(in.readUnsignedByte(), MAX_EQUIPPED);
		int anchorBits = in.readUnsignedByte();
		Set<Anchor> anchors = EnumSet.noneOf(Anchor.class);
		for (Anchor anchor : Anchor.values()) {
			if ((anchorBits & (1 << anchor.ordinal())) != 0) {
				anchors.add(anchor);
			}
		}
		int[] v = new int[11];
		for (int i = 0; i < v.length; i++) {
			v[i] = in.readInt();
		}
		ModelLimits limits = new ModelLimits(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10]).clampToHard();
		String message = in.readString(MAX_MESSAGE_CHARS);
		return new ServerPolicy((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0, (flags & 32) != 0,
			maxEquipped, anchors, limits, message);
	}

	/** Splits an asset into chunks of {@code chunkSize}. */
	public static List<byte[]> split(byte[] asset, int chunkSize) {
		List<byte[]> chunks = new ArrayList<>();
		for (int offset = 0; offset < asset.length; offset += chunkSize) {
			int length = Math.min(chunkSize, asset.length - offset);
			byte[] chunk = new byte[length];
			System.arraycopy(asset, offset, chunk, 0, length);
			chunks.add(chunk);
		}
		return chunks;
	}
}

package dev.orbit.cosmetic.server.fabric;

import dev.orbit.cosmetic.common.protocol.OrbitProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The Orbit plugin channel: raw protocol bytes, identical to what the client mod and the Paper plugin use. */
public record OrbitPayload(byte[] data) implements CustomPacketPayload {
	public static final Type<OrbitPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(OrbitProtocol.NAMESPACE, OrbitProtocol.CHANNEL_PATH));
	public static final StreamCodec<FriendlyByteBuf, OrbitPayload> CODEC = StreamCodec.of(
		(buffer, payload) -> buffer.writeBytes(payload.data),
		buffer -> {
			byte[] data = new byte[buffer.readableBytes()];
			buffer.readBytes(data);
			return new OrbitPayload(data);
		}
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

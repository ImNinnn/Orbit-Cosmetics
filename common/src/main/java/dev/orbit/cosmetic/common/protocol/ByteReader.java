package dev.orbit.cosmetic.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Bounds-checked big-endian reader. Every read validates against the remaining bytes. */
public final class ByteReader {
	private final byte[] data;
	private int position;

	public ByteReader(byte[] data) {
		this.data = data;
	}

	private void need(int count) throws ProtocolException {
		if (count < 0 || data.length - position < count) {
			throw new ProtocolException("Message truncated");
		}
	}

	public int readUnsignedByte() throws ProtocolException {
		need(1);
		return data[position++] & 0xFF;
	}

	public boolean readBoolean() throws ProtocolException {
		int value = readUnsignedByte();
		if (value > 1) {
			throw new ProtocolException("Invalid boolean");
		}
		return value == 1;
	}

	public int readUnsignedShort() throws ProtocolException {
		need(2);
		int value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
		position += 2;
		return value;
	}

	public int readInt() throws ProtocolException {
		need(4);
		int value = ((data[position] & 0xFF) << 24) | ((data[position + 1] & 0xFF) << 16) | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
		position += 4;
		return value;
	}

	public long readLong() throws ProtocolException {
		long high = readInt() & 0xFFFFFFFFL;
		long low = readInt() & 0xFFFFFFFFL;
		return (high << 32) | low;
	}

	public UUID readUuid() throws ProtocolException {
		return new UUID(readLong(), readLong());
	}

	public byte[] readFixed(int length) throws ProtocolException {
		need(length);
		byte[] result = new byte[length];
		System.arraycopy(data, position, result, 0, length);
		position += length;
		return result;
	}

	/** Reads a u32-length-prefixed byte array no longer than {@code maxLength}. */
	public byte[] readBlob(int maxLength) throws ProtocolException {
		int length = readInt();
		if (length < 0 || length > maxLength) {
			throw new ProtocolException("Blob length " + length + " exceeds " + maxLength);
		}
		return readFixed(length);
	}

	public String readString(int maxChars) throws ProtocolException {
		int length = readUnsignedShort();
		if (length > maxChars * 4) {
			throw new ProtocolException("String too long");
		}
		need(length);
		String value = new String(data, position, length, StandardCharsets.UTF_8);
		position += length;
		if (value.length() > maxChars) {
			throw new ProtocolException("String too long");
		}
		return value;
	}

	public void expectEnd() throws ProtocolException {
		if (position != data.length) {
			throw new ProtocolException("Unexpected trailing bytes");
		}
	}
}

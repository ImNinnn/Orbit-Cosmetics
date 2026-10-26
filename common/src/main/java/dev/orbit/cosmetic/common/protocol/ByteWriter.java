package dev.orbit.cosmetic.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Big-endian writer for Orbit messages. */
public final class ByteWriter {
	private byte[] buffer;
	private int size;

	public ByteWriter(int initialCapacity) {
		buffer = new byte[Math.max(16, initialCapacity)];
	}

	private void ensure(int extra) {
		if (size + extra > buffer.length) {
			buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + extra));
		}
	}

	public ByteWriter writeByte(int value) {
		ensure(1);
		buffer[size++] = (byte) value;
		return this;
	}

	public ByteWriter writeBoolean(boolean value) {
		return writeByte(value ? 1 : 0);
	}

	public ByteWriter writeShort(int value) {
		ensure(2);
		buffer[size++] = (byte) (value >>> 8);
		buffer[size++] = (byte) value;
		return this;
	}

	public ByteWriter writeInt(int value) {
		ensure(4);
		buffer[size++] = (byte) (value >>> 24);
		buffer[size++] = (byte) (value >>> 16);
		buffer[size++] = (byte) (value >>> 8);
		buffer[size++] = (byte) value;
		return this;
	}

	public ByteWriter writeLong(long value) {
		writeInt((int) (value >>> 32));
		return writeInt((int) value);
	}

	public ByteWriter writeUuid(UUID uuid) {
		writeLong(uuid.getMostSignificantBits());
		return writeLong(uuid.getLeastSignificantBits());
	}

	public ByteWriter writeFixed(byte[] bytes) {
		ensure(bytes.length);
		System.arraycopy(bytes, 0, buffer, size, bytes.length);
		size += bytes.length;
		return this;
	}

	/** Writes a length-prefixed (u32) byte array. */
	public ByteWriter writeBlob(byte[] bytes, int offset, int length) {
		writeInt(length);
		ensure(length);
		System.arraycopy(bytes, offset, buffer, size, length);
		size += length;
		return this;
	}

	/** Writes a u16-length-prefixed UTF-8 string, truncated to {@code maxChars}. */
	public ByteWriter writeString(String value, int maxChars) {
		String text = value == null ? "" : value;
		if (text.length() > maxChars) {
			text = text.substring(0, maxChars);
		}
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		writeShort(bytes.length);
		return writeFixed(bytes);
	}

	public int size() {
		return size;
	}

	public byte[] toByteArray() {
		return Arrays.copyOf(buffer, size);
	}
}

package dev.orbit.cosmetic.common.util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Minimal RGBA8 PNG encoder, used to embed the reference textures in generated templates. */
public final class PngWriter {
	private PngWriter() {
	}

	/** @param argb pixels in row-major order, packed as 0xAARRGGBB */
	public static byte[] write(int width, int height, int[] argb) {
		if (argb.length != width * height) {
			throw new IllegalArgumentException("Pixel count does not match dimensions");
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});

			ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
			DataOutputStream header = new DataOutputStream(ihdr);
			header.writeInt(width);
			header.writeInt(height);
			header.writeByte(8); // bit depth
			header.writeByte(6); // RGBA
			header.writeByte(0);
			header.writeByte(0);
			header.writeByte(0);
			writeChunk(out, "IHDR", ihdr.toByteArray());

			ByteArrayOutputStream raw = new ByteArrayOutputStream();
			try (DeflaterOutputStream deflate = new DeflaterOutputStream(raw, new Deflater(Deflater.BEST_COMPRESSION))) {
				for (int y = 0; y < height; y++) {
					deflate.write(0); // filter: none
					for (int x = 0; x < width; x++) {
						int c = argb[y * width + x];
						deflate.write((c >> 16) & 0xFF);
						deflate.write((c >> 8) & 0xFF);
						deflate.write(c & 0xFF);
						deflate.write((c >>> 24) & 0xFF);
					}
				}
			}
			writeChunk(out, "IDAT", raw.toByteArray());
			writeChunk(out, "IEND", new byte[0]);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
		DataOutputStream stream = new DataOutputStream(out);
		byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		stream.writeInt(data.length);
		stream.write(typeBytes);
		stream.write(data);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		stream.writeInt((int) crc.getValue());
	}
}

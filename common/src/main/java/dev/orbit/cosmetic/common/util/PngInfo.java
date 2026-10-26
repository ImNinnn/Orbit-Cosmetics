package dev.orbit.cosmetic.common.util;

/**
 * Reads the dimensions of a PNG from its IHDR chunk without decoding pixel data.
 * Used to enforce texture limits before any image decoder sees untrusted bytes.
 */
public record PngInfo(int width, int height, int bitDepth, int colorType) {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	/** @return the header info, or null if the bytes are not a well-formed PNG header */
	public static PngInfo read(byte[] png) {
		if (png == null || png.length < 33) {
			return null;
		}
		for (int i = 0; i < SIGNATURE.length; i++) {
			if (png[i] != SIGNATURE[i]) {
				return null;
			}
		}
		int chunkLength = readInt(png, 8);
		if (chunkLength != 13 || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') {
			return null;
		}
		int width = readInt(png, 16);
		int height = readInt(png, 20);
		if (width <= 0 || height <= 0) {
			return null;
		}
		return new PngInfo(width, height, png[24] & 0xFF, png[25] & 0xFF);
	}

	private static int readInt(byte[] data, int offset) {
		return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
	}
}

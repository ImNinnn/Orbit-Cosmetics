package dev.orbit.cosmetic.common.util;

import java.util.Base64;

/** Decodes the {@code data:image/png;base64,...} URLs Blockbench uses to embed textures. */
public final class DataUrls {
	private static final String PNG_PREFIX = "data:image/png;base64,";

	private DataUrls() {
	}

	/**
	 * @param maxDecodedBytes upper bound checked before decoding so a huge string cannot allocate a huge array
	 * @return the decoded PNG bytes, or null if the URL is not a base64 PNG data URL or is too large
	 */
	public static byte[] decodePng(String url, int maxDecodedBytes) {
		if (url == null || !url.regionMatches(true, 0, PNG_PREFIX, 0, PNG_PREFIX.length())) {
			return null;
		}
		int payloadLength = url.length() - PNG_PREFIX.length();
		long estimated = (long) payloadLength * 3 / 4;
		if (estimated > maxDecodedBytes) {
			return null;
		}
		try {
			return Base64.getMimeDecoder().decode(url.substring(PNG_PREFIX.length()));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static String encodePng(byte[] png) {
		return PNG_PREFIX + Base64.getEncoder().encodeToString(png);
	}
}

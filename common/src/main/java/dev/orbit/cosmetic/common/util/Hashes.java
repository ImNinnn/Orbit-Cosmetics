package dev.orbit.cosmetic.common.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. Asset identity in Orbit is always the SHA-256 of the canonical asset bytes. */
public final class Hashes {
	public static final int SHA256_BYTES = 32;
	private static final HexFormat HEX = HexFormat.of();

	private Hashes() {
	}

	public static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	public static String toHex(byte[] hash) {
		return HEX.formatHex(hash);
	}

	/** Parses a 64 character lowercase/uppercase hex hash, or returns null if it is not one. */
	public static byte[] fromHex(String hex) {
		if (hex == null || hex.length() != SHA256_BYTES * 2) {
			return null;
		}
		try {
			return HEX.parseHex(hex);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static boolean isValidHex(String hex) {
		return fromHex(hex) != null;
	}
}

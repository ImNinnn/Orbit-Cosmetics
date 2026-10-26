package dev.orbit.cosmetic.common.protocol;

import dev.orbit.cosmetic.common.util.Hashes;

/** Identity of a cosmetic asset: the SHA-256 of its canonical bytes, as lowercase hex. */
public record AssetId(String hex) implements Comparable<AssetId> {
	public AssetId {
		if (!Hashes.isValidHex(hex)) {
			throw new IllegalArgumentException("Not a SHA-256 hex string");
		}
		hex = hex.toLowerCase(java.util.Locale.ROOT);
	}

	public static AssetId of(byte[] assetBytes) {
		return new AssetId(Hashes.toHex(Hashes.sha256(assetBytes)));
	}

	public static AssetId fromBytes(byte[] hash) {
		return new AssetId(Hashes.toHex(hash));
	}

	/** @return the id, or null if the text is not a valid hash */
	public static AssetId tryParse(String hex) {
		return Hashes.isValidHex(hex) ? new AssetId(hex) : null;
	}

	public byte[] bytes() {
		return Hashes.fromHex(hex);
	}

	public boolean matches(byte[] assetBytes) {
		return hex.equals(Hashes.toHex(Hashes.sha256(assetBytes)));
	}

	public String shortHex() {
		return hex.substring(0, 12);
	}

	@Override
	public int compareTo(AssetId other) {
		return hex.compareTo(other.hex);
	}

	@Override
	public String toString() {
		return hex;
	}
}

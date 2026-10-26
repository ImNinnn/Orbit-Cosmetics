package dev.orbit.cosmetic.common.model;

/**
 * A texture embedded in a model.
 *
 * @param png the raw PNG bytes (validated header only; decoding happens on the client)
 * @param translucent whether any pixel has partial alpha, which selects a translucent render type.
 *                    Determined by the client after decoding; the parser sets false.
 */
public record TextureData(String name, byte[] png, int width, int height, boolean translucent) {
	public TextureData withTranslucent(boolean value) {
		return new TextureData(name, png, width, height, value);
	}
}

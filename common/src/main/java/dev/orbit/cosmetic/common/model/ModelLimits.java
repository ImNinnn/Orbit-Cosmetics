package dev.orbit.cosmetic.common.model;

/**
 * Resource limits applied while parsing untrusted models. Values are clamped to {@link #HARD} so that a
 * misconfigured server or client can never disable the protection entirely.
 */
public record ModelLimits(
	int maxFileBytes,
	int maxElements,
	int maxBones,
	int maxDepth,
	int maxQuads,
	int maxTextures,
	int maxTextureSize,
	int maxTotalTextureBytes,
	int maxAnimations,
	int maxKeyframes,
	int maxExpressionLength
) {
	/** Absolute ceilings. Nothing is ever accepted above these, whatever the configuration says. */
	public static final ModelLimits HARD = new ModelLimits(8 * 1024 * 1024, 4096, 1024, 32, 24576, 16, 1024, 6 * 1024 * 1024, 64, 8192, 1024);

	/** Defaults used by the client for local files and by the server unless configured otherwise. */
	public static final ModelLimits DEFAULT = new ModelLimits(2 * 1024 * 1024, 1024, 256, 16, 6144, 8, 512, 2 * 1024 * 1024, 32, 4096, 512);

	public ModelLimits clampToHard() {
		return new ModelLimits(
			clamp(maxFileBytes, 1024, HARD.maxFileBytes),
			clamp(maxElements, 1, HARD.maxElements),
			clamp(maxBones, 1, HARD.maxBones),
			clamp(maxDepth, 1, HARD.maxDepth),
			clamp(maxQuads, 6, HARD.maxQuads),
			clamp(maxTextures, 1, HARD.maxTextures),
			clamp(maxTextureSize, 1, HARD.maxTextureSize),
			clamp(maxTotalTextureBytes, 1024, HARD.maxTotalTextureBytes),
			clamp(maxAnimations, 0, HARD.maxAnimations),
			clamp(maxKeyframes, 0, HARD.maxKeyframes),
			clamp(maxExpressionLength, 1, HARD.maxExpressionLength)
		);
	}

	/** Combines two limit sets, keeping the stricter value of each field. */
	public ModelLimits strictest(ModelLimits other) {
		return new ModelLimits(
			Math.min(maxFileBytes, other.maxFileBytes),
			Math.min(maxElements, other.maxElements),
			Math.min(maxBones, other.maxBones),
			Math.min(maxDepth, other.maxDepth),
			Math.min(maxQuads, other.maxQuads),
			Math.min(maxTextures, other.maxTextures),
			Math.min(maxTextureSize, other.maxTextureSize),
			Math.min(maxTotalTextureBytes, other.maxTotalTextureBytes),
			Math.min(maxAnimations, other.maxAnimations),
			Math.min(maxKeyframes, other.maxKeyframes),
			Math.min(maxExpressionLength, other.maxExpressionLength)
		);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}

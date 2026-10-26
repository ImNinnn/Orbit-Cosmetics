package dev.orbit.cosmetic.common.anim;

/**
 * How one axis of a part reacts to movement.
 *
 * @param strength output per unit of the source (pixels or degrees per block/second); negative inverts
 * @param max largest absolute output (pixels or degrees)
 * @param resistance 0 follows the movement instantly; values near 1 react slowly and settle smoothly
 */
public record AxisDrive(PhysicsSource source, float strength, float max, float resistance) {
	public static final float MAX_POSITION_STRENGTH = 8f;
	public static final float MAX_ROTATION_STRENGTH = 45f;
	public static final float MAX_POSITION = 16f;
	public static final float MAX_ROTATION = 180f;
	public static final float MAX_RESISTANCE = 0.98f;
	public static final AxisDrive OFF = new AxisDrive(PhysicsSource.NONE, 0, 0, 0.5f);

	public AxisDrive {
		if (source == null) {
			source = PhysicsSource.NONE;
		}
		strength = finite(strength, 0);
		max = Math.max(0, finite(max, 0));
		resistance = Math.max(0, Math.min(MAX_RESISTANCE, finite(resistance, 0.5f)));
	}

	/** Limits values to the ranges allowed for a position (axis 0-2) or rotation (axis 3-5) axis. */
	public AxisDrive clamped(boolean rotation) {
		float strengthLimit = rotation ? MAX_ROTATION_STRENGTH : MAX_POSITION_STRENGTH;
		float maxLimit = rotation ? MAX_ROTATION : MAX_POSITION;
		return new AxisDrive(source, Math.max(-strengthLimit, Math.min(strengthLimit, strength)), Math.min(maxLimit, max), resistance);
	}

	public boolean active() {
		return source != PhysicsSource.NONE && strength != 0 && max > 0;
	}

	public AxisDrive withSource(PhysicsSource value) {
		return new AxisDrive(value, strength, max, resistance);
	}

	public AxisDrive withStrength(float value) {
		return new AxisDrive(source, value, max, resistance);
	}

	public AxisDrive withMax(float value) {
		return new AxisDrive(source, strength, value, resistance);
	}

	public AxisDrive withResistance(float value) {
		return new AxisDrive(source, strength, max, value);
	}

	private static float finite(float value, float fallback) {
		return Float.isFinite(value) ? value : fallback;
	}
}

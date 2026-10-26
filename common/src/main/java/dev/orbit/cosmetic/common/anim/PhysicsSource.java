package dev.orbit.cosmetic.common.anim;

/**
 * Movement a physics axis reacts to. Speeds are in blocks per second relative to the player's body;
 * {@link #TURN} is in quarter turns (90 degrees) per second. Ordinals are part of the protocol: append only.
 */
public enum PhysicsSource {
	NONE,
	/** Moving forward is positive, backward negative. */
	FORWARD,
	/** Moving to the player's right is positive. */
	SIDEWAYS,
	/** Moving up is positive, falling negative. */
	VERTICAL,
	/** Horizontal speed in any direction (never negative). */
	SPEED,
	/** Turning the body to the right is positive. */
	TURN;

	private static final PhysicsSource[] VALUES = values();

	public static PhysicsSource byOrdinal(int ordinal) {
		return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : NONE;
	}
}

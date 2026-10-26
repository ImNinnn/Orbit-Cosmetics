package dev.orbit.cosmetic.common.protocol;

/** Player state Orbit synchronizes because vanilla does not send it to other clients. */
public final class PlayerFlags {
	/** Creative / spectator style ability flight. */
	public static final int FLYING = 1;
	public static final int KNOWN = FLYING;

	private PlayerFlags() {
	}
}

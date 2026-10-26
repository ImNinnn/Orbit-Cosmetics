package dev.orbit.cosmetic.common.anim;

/** How an animation behaves when it reaches its end. */
public enum LoopMode {
	/** Restart from the beginning while the trigger is active. */
	LOOP,
	/** Play once, then return to the default pose (other animations such as IDLE take over again). */
	ONCE,
	/** Play once and hold the last frame while the trigger is active. */
	HOLD;

	private static final LoopMode[] VALUES = values();

	public static LoopMode fromBlockbench(String name) {
		if (name == null) {
			return ONCE;
		}
		return switch (name) {
			case "loop" -> LOOP;
			case "hold" -> HOLD;
			default -> ONCE;
		};
	}

	public static LoopMode byOrdinal(int ordinal) {
		return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : ONCE;
	}
}

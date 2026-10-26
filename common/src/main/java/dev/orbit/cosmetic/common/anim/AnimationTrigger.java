package dev.orbit.cosmetic.common.anim;

import java.util.Locale;

/** Player states an animation can be assigned to. Ordinals are part of the network protocol: append only. */
public enum AnimationTrigger {
	/** Not assigned: the animation never plays. */
	NONE(0, LoopMode.LOOP),
	/** Plays all the time. */
	ALWAYS(0, LoopMode.LOOP),
	IDLE(10, LoopMode.LOOP),
	WALK(20, LoopMode.LOOP),
	SPRINT(30, LoopMode.LOOP),
	SNEAK(40, LoopMode.LOOP),
	CROUCH(40, LoopMode.LOOP),
	SWIM(50, LoopMode.LOOP),
	FLY(50, LoopMode.LOOP),
	FALL(60, LoopMode.LOOP),
	/** Starts when the player leaves the ground by jumping; lasts until landing. */
	JUMP(70, LoopMode.ONCE),
	/**
	 * Plays once at random moments, waiting a random time between plays (see {@link AnimationSettings#minWait()}).
	 * The schedule is derived from the shared game time, so every client plays it at the same moment.
	 */
	RANDOM(15, LoopMode.ONCE);

	private static final AnimationTrigger[] VALUES = values();

	private final int defaultPriority;
	private final LoopMode defaultLoop;

	AnimationTrigger(int defaultPriority, LoopMode defaultLoop) {
		this.defaultPriority = defaultPriority;
		this.defaultLoop = defaultLoop;
	}

	public int defaultPriority() {
		return defaultPriority;
	}

	public LoopMode defaultLoop() {
		return defaultLoop;
	}

	public int bit() {
		return 1 << ordinal();
	}

	public static AnimationTrigger byOrdinal(int ordinal) {
		return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : NONE;
	}

	/**
	 * Suggests a trigger from an animation name such as {@code SPRINT}, {@code animation.hat.walk} or
	 * {@code idle_loop}. Returns {@link #NONE} for names that are not recognized.
	 */
	public static AnimationTrigger suggest(String animationName) {
		if (animationName == null) {
			return NONE;
		}
		String name = animationName.trim().toUpperCase(Locale.ROOT);
		int dot = name.lastIndexOf('.');
		if (dot >= 0) {
			name = name.substring(dot + 1);
		}
		name = name.replace('-', '_').replace(' ', '_');
		AnimationTrigger exact = match(name);
		if (exact != NONE) {
			return exact;
		}
		for (String part : name.split("_")) {
			AnimationTrigger partial = match(part);
			if (partial != NONE) {
				return partial;
			}
		}
		return NONE;
	}

	private static AnimationTrigger match(String token) {
		return switch (token) {
			case "IDLE", "STAND", "STANDING" -> IDLE;
			case "WALK", "WALKING", "MOVE", "MOVING" -> WALK;
			case "SPRINT", "SPRINTING", "RUN", "RUNNING" -> SPRINT;
			case "JUMP", "JUMPING" -> JUMP;
			case "FALL", "FALLING" -> FALL;
			case "SNEAK", "SNEAKING", "SHIFT" -> SNEAK;
			case "CROUCH", "CROUCHING" -> CROUCH;
			case "SWIM", "SWIMMING" -> SWIM;
			case "FLY", "FLYING", "GLIDE", "GLIDING", "ELYTRA" -> FLY;
			case "ALWAYS", "PASSIVE", "AMBIENT", "CONSTANT" -> ALWAYS;
			case "RANDOM", "FIDGET", "OCCASIONAL" -> RANDOM;
			default -> NONE;
		};
	}
}

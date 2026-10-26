package dev.orbit.cosmetic.common.anim;

/**
 * How the wearer configured one animation. These settings are synchronized so every client plays the
 * cosmetic the same way.
 *
 * @param speed playback speed multiplier (0.1 to 4.0)
 * @param priority 0 to 100; higher wins when animations affect the same bone channel
 * @param interrupt when playing, suppresses all animations of this cosmetic with lower priority
 * @param minWait for {@link AnimationTrigger#RANDOM}: shortest pause between plays, in seconds
 * @param maxWait for {@link AnimationTrigger#RANDOM}: longest pause between plays, in seconds
 */
public record AnimationSettings(AnimationTrigger trigger, LoopMode loop, float speed, int priority, boolean interrupt, float minWait, float maxWait) {
	public static final float MIN_SPEED = 0.1f;
	public static final float MAX_SPEED = 4.0f;
	public static final int MAX_PRIORITY = 100;
	public static final float MAX_WAIT = 600f;
	public static final float DEFAULT_MIN_WAIT = 5f;
	public static final float DEFAULT_MAX_WAIT = 15f;

	public AnimationSettings {
		if (trigger == null) {
			trigger = AnimationTrigger.NONE;
		}
		if (loop == null) {
			loop = LoopMode.LOOP;
		}
		if (!(speed >= MIN_SPEED)) {
			speed = speed > 0 ? MIN_SPEED : 1.0f;
		}
		speed = Math.min(MAX_SPEED, speed);
		priority = Math.max(0, Math.min(MAX_PRIORITY, priority));
		minWait = Float.isFinite(minWait) ? Math.max(0, Math.min(MAX_WAIT, minWait)) : DEFAULT_MIN_WAIT;
		maxWait = Float.isFinite(maxWait) ? Math.max(minWait, Math.min(MAX_WAIT, maxWait)) : Math.max(minWait, DEFAULT_MAX_WAIT);
	}

	public AnimationSettings(AnimationTrigger trigger, LoopMode loop, float speed, int priority, boolean interrupt) {
		this(trigger, loop, speed, priority, interrupt, DEFAULT_MIN_WAIT, DEFAULT_MAX_WAIT);
	}

	/** Defaults for a clip: trigger suggested from its name, loop mode from the file when it is meaningful. */
	public static AnimationSettings defaultsFor(AnimationClip clip) {
		AnimationTrigger trigger = AnimationTrigger.suggest(clip.name());
		LoopMode loop = clip.defaultLoop();
		if ((trigger == AnimationTrigger.JUMP || trigger == AnimationTrigger.RANDOM) && loop == LoopMode.LOOP) {
			loop = LoopMode.ONCE;
		}
		return new AnimationSettings(trigger, loop, 1.0f, trigger.defaultPriority(), false);
	}

	public AnimationSettings withTrigger(AnimationTrigger value) {
		return new AnimationSettings(value, loop, speed, value.defaultPriority(), interrupt, minWait, maxWait);
	}

	public AnimationSettings withLoop(LoopMode value) {
		return new AnimationSettings(trigger, value, speed, priority, interrupt, minWait, maxWait);
	}

	public AnimationSettings withSpeed(float value) {
		return new AnimationSettings(trigger, loop, value, priority, interrupt, minWait, maxWait);
	}

	public AnimationSettings withPriority(int value) {
		return new AnimationSettings(trigger, loop, speed, value, interrupt, minWait, maxWait);
	}

	public AnimationSettings withInterrupt(boolean value) {
		return new AnimationSettings(trigger, loop, speed, priority, value, minWait, maxWait);
	}

	/** Sets the random wait range; the other bound is pushed along if the range would become empty. */
	public AnimationSettings withMinWait(float value) {
		return new AnimationSettings(trigger, loop, speed, priority, interrupt, value, Math.max(value, maxWait));
	}

	public AnimationSettings withMaxWait(float value) {
		return new AnimationSettings(trigger, loop, speed, priority, interrupt, Math.min(value, minWait), value);
	}
}

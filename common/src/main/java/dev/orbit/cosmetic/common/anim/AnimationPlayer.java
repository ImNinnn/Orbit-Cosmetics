package dev.orbit.cosmetic.common.anim;

import dev.orbit.cosmetic.common.anim.molang.MolangContext;

import java.util.List;

/**
 * Plays the animations of one cosmetic on one wearer. {@link #tick} advances trigger state once per game tick;
 * {@link #evaluate} can be called every frame with a partial tick time.
 *
 * <p>Times are ticks of the shared game time (smoothed by {@link AnimationClock}), so
 * {@link AnimationTrigger#RANDOM} animations, which are scheduled from it, start at the same moment on every
 * client.
 */
public final class AnimationPlayer {
	private final List<AnimationClip> clips;
	private final long seed;
	private AnimationSettings[] settings;
	private RandomSchedule[] schedules;
	private final double[] startTick;
	private final boolean[] playing;
	private double lastTime = Double.NaN;

	/**
	 * @param seed base seed for random schedules; use {@link RandomSchedule#seed} with index 0 so every
	 *             client derives the same value
	 */
	public AnimationPlayer(List<AnimationClip> clips, AnimationSettings[] settings, long seed) {
		this.clips = clips;
		this.seed = seed;
		this.startTick = new double[clips.size()];
		this.playing = new boolean[clips.size()];
		setSettings(settings);
	}

	public AnimationPlayer(List<AnimationClip> clips, AnimationSettings[] settings) {
		this(clips, settings, 0);
	}

	/** Replaces the settings; missing entries fall back to {@link AnimationSettings#defaultsFor}. */
	public void setSettings(AnimationSettings[] newSettings) {
		AnimationSettings[] resolved = new AnimationSettings[clips.size()];
		for (int i = 0; i < resolved.length; i++) {
			resolved[i] = newSettings != null && i < newSettings.length && newSettings[i] != null
				? newSettings[i]
				: AnimationSettings.defaultsFor(clips.get(i));
		}
		this.settings = resolved;
		this.schedules = new RandomSchedule[resolved.length];
		for (int i = 0; i < resolved.length; i++) {
			AnimationSettings s = resolved[i];
			if (s.trigger() == AnimationTrigger.RANDOM) {
				double playTicks = clips.get(i).length() / s.speed() * 20.0;
				schedules[i] = new RandomSchedule(seed + i * 0x9E3779B97F4A7C15L, playTicks, s.minWait() * 20.0, s.maxWait() * 20.0);
			}
		}
	}

	public AnimationSettings[] settings() {
		return settings;
	}

	public void tick(double tick, MotionTracker motion) {
		if (tick < lastTime) {
			// The timeline moved back (new level or server): start times from the old one would lie in the future.
			restart();
		}
		lastTime = tick;
		int active = motion.active();
		int started = motion.started();
		for (int i = 0; i < clips.size(); i++) {
			AnimationTrigger trigger = settings[i].trigger();
			if (trigger == AnimationTrigger.NONE) {
				playing[i] = false;
				continue;
			}
			if (trigger == AnimationTrigger.RANDOM) {
				double start = schedules[i].activeStart(tick);
				playing[i] = !Double.isNaN(start);
				if (playing[i]) {
					startTick[i] = start;
				}
				continue;
			}
			boolean condition = (active & trigger.bit()) != 0;
			if (!condition) {
				playing[i] = false;
			} else if ((started & trigger.bit()) != 0 || !playing[i]) {
				playing[i] = true;
				startTick[i] = tick;
			}
		}
	}

	/** Restarts every animation, e.g. after the settings changed. */
	public void restart() {
		java.util.Arrays.fill(playing, false);
	}

	/**
	 * Writes the blended pose at {@code time} (ticks, including the partial tick) into {@code out}.
	 * {@code context} must already hold the wearer's queries; {@code animTime} is set per clip.
	 */
	public void evaluate(double time, MolangContext context, PoseBuffer out) {
		out.reset();
		int interruptPriority = Integer.MIN_VALUE;
		for (int i = 0; i < clips.size(); i++) {
			if (isRunning(i, time) && settings[i].interrupt()) {
				interruptPriority = Math.max(interruptPriority, settings[i].priority());
			}
		}
		for (int i = 0; i < clips.size(); i++) {
			if (!isRunning(i, time)) {
				continue;
			}
			AnimationSettings s = settings[i];
			if (s.priority() < interruptPriority) {
				continue;
			}
			AnimationClip clip = clips.get(i);
			LoopMode loop = loopMode(i);
			double clipTime = clip.clipTime(elapsedSeconds(i, time), loop);
			int priority = s.priority();
			context.resetVariables();
			clip.sample(clipTime, loop == LoopMode.LOOP, context, (bone, channel, x, y, z) -> out.apply(bone, channel, x, y, z, priority));
		}
	}

	private boolean isRunning(int index, double time) {
		return playing[index] && !clips.get(index).isFinished(elapsedSeconds(index, time), loopMode(index));
	}

	/** Random animations always play once per occurrence. */
	private LoopMode loopMode(int index) {
		return settings[index].trigger() == AnimationTrigger.RANDOM ? LoopMode.ONCE : settings[index].loop();
	}

	private double elapsedSeconds(int index, double time) {
		return Math.max(0, time - startTick[index]) / 20.0 * settings[index].speed();
	}
}

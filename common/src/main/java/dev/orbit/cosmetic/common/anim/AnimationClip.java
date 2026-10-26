package dev.orbit.cosmetic.common.anim;

import dev.orbit.cosmetic.common.anim.molang.MolangContext;

import java.util.List;

/**
 * A Blockbench animation converted for playback.
 *
 * @param length length in seconds; never negative
 * @param defaultLoop loop mode stored in the .bbmodel file
 */
public record AnimationClip(String name, LoopMode defaultLoop, float length, float startDelay, float loopDelay, List<BoneTrack> tracks) {
	/**
	 * Maps elapsed playback time to the time inside the clip for the given mode.
	 *
	 * @return the clip time, or a negative value when a {@link LoopMode#ONCE} clip has finished
	 */
	public double clipTime(double elapsed, LoopMode mode) {
		double t = elapsed - startDelay;
		if (t < 0) {
			return 0;
		}
		if (length <= 0) {
			return mode == LoopMode.ONCE && t > 0 ? -1 : 0;
		}
		return switch (mode) {
			case LOOP -> {
				double cycle = length + Math.max(0, loopDelay);
				double inCycle = t % cycle;
				yield Math.min(inCycle, length);
			}
			case HOLD -> Math.min(t, length);
			case ONCE -> t > length ? -1 : t;
		};
	}

	/** @return true once a non-looping clip has played to its end */
	public boolean isFinished(double elapsed, LoopMode mode) {
		return mode == LoopMode.ONCE && clipTime(elapsed, mode) < 0;
	}

	/**
	 * Samples every track at {@code clipTime} and hands each value to the sink.
	 * Values are already in Minecraft model space: rotations in radians, positions in pixels.
	 */
	public void sample(double clipTime, boolean looping, MolangContext context, Sink sink) {
		context.animTime = clipTime;
		double[] value = new double[3];
		for (BoneTrack track : tracks) {
			if (track.rotation() != null && track.rotation().sample(clipTime, looping, context, value)) {
				sink.accept(track.bone(), PoseBuffer.ROTATION, Math.toRadians(value[0]), Math.toRadians(value[1]), Math.toRadians(value[2]));
			}
			if (track.position() != null && track.position().sample(clipTime, looping, context, value)) {
				sink.accept(track.bone(), PoseBuffer.POSITION, value[0], value[1], value[2]);
			}
			if (track.scale() != null && track.scale().sample(clipTime, looping, context, value)) {
				sink.accept(track.bone(), PoseBuffer.SCALE, value[0], value[1], value[2]);
			}
		}
	}

	@FunctionalInterface
	public interface Sink {
		void accept(int bone, int channel, double x, double y, double z);
	}
}

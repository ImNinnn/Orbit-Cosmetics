package dev.orbit.cosmetic.common.anim;

/**
 * Deterministic schedule for {@link AnimationTrigger#RANDOM} animations.
 *
 * <p>Plays and pauses alternate: each pause lasts a pseudo-random time between the minimum and maximum wait,
 * drawn from a hash of the seed, the epoch and the play's index. The time base is the game time shared by
 * the server with every client, and the seed is derived from the wearer, the asset and the animation, so all
 * clients compute exactly the same play times without any network traffic. The sequence restarts at every
 * epoch (one Minecraft day), which bounds the work needed to find the current play after joining.
 */
public final class RandomSchedule {
	/** Ticks per epoch: one Minecraft day. */
	public static final long EPOCH = 24000;

	private final long seed;
	private final double playTicks;
	private final double minWaitTicks;
	private final double maxWaitTicks;

	private long cachedEpoch = Long.MIN_VALUE;
	private long cachedIndex;
	private double cachedStart;

	/**
	 * @param playTicks how long one play lasts (clip length divided by speed), in ticks
	 */
	public RandomSchedule(long seed, double playTicks, double minWaitTicks, double maxWaitTicks) {
		this.seed = seed;
		this.playTicks = Math.max(1, playTicks);
		this.minWaitTicks = Math.max(0, minWaitTicks);
		this.maxWaitTicks = Math.max(this.minWaitTicks, maxWaitTicks);
	}

	/**
	 * @param time game time in ticks
	 * @return the start time of the play covering {@code time}, or {@link Double#NaN} while waiting
	 */
	public double activeStart(double time) {
		long epoch = Math.floorDiv((long) Math.floor(time), EPOCH);
		double epochStart = (double) epoch * EPOCH;
		if (epoch != cachedEpoch || time < cachedStart) {
			cachedEpoch = epoch;
			cachedIndex = 0;
			cachedStart = epochStart + waitTicks(epoch, 0);
		}
		while (cachedStart + playTicks <= time) {
			cachedIndex++;
			cachedStart += playTicks + waitTicks(epoch, cachedIndex);
		}
		return cachedStart <= time ? cachedStart : Double.NaN;
	}

	private double waitTicks(long epoch, long index) {
		double unit = (mix(seed ^ mix(epoch * 0x9E3779B97F4A7C15L + index)) >>> 11) * 0x1.0p-53;
		return minWaitTicks + unit * (maxWaitTicks - minWaitTicks);
	}

	/** SplitMix64 finalizer. */
	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** Seed for one animation of one asset on one wearer. Identical on every client. */
	public static long seed(java.util.UUID wearer, String assetHex, int animationIndex) {
		long h = wearer.getMostSignificantBits() * 31 + wearer.getLeastSignificantBits();
		h = h * 31 + assetHex.hashCode();
		return mix(h * 31 + animationIndex);
	}
}

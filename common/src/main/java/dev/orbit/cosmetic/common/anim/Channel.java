package dev.orbit.cosmetic.common.anim;

import dev.orbit.cosmetic.common.anim.molang.MolangContext;

/**
 * The keyframes of one bone channel (rotation, position or scale), sorted by time.
 * Sampling follows Blockbench's {@code BoneAnimator.interpolate}.
 *
 * @param signs per-axis factors that convert Blockbench values to Minecraft model space
 */
public record Channel(Keyframe[] keyframes, float[] signs) {
	private static final float EPSILON = 1f / 1200f;

	/**
	 * Writes the channel value at {@code time} into {@code out[0..2]}.
	 *
	 * @param looping whether the animation loops (enables Blockbench's catmull-rom wrap-around)
	 * @return false if the channel has no keyframes
	 */
	public boolean sample(double time, boolean looping, MolangContext context, double[] out) {
		Keyframe[] frames = keyframes;
		if (frames.length == 0) {
			return false;
		}
		int beforeIndex = -1;
		int afterIndex = -1;
		for (int i = 0; i < frames.length; i++) {
			if (frames[i].time() < time) {
				beforeIndex = i;
			} else {
				afterIndex = i;
				break;
			}
		}
		Keyframe before = beforeIndex >= 0 ? frames[beforeIndex] : null;
		Keyframe after = afterIndex >= 0 ? frames[afterIndex] : null;

		Keyframe exact = null;
		if (before != null && Math.abs(before.time() - time) < EPSILON) {
			exact = before;
		} else if (after != null && Math.abs(after.time() - time) < EPSILON) {
			exact = after;
		} else if (before != null && (before.interpolation() == Interpolation.STEP || after == null)) {
			exact = before;
		} else if (after != null && before == null) {
			exact = after;
		}

		if (exact != null) {
			boolean usePre = exact.time() > time;
			for (int axis = 0; axis < 3; axis++) {
				out[axis] = (usePre ? exact.pre(axis, context) : exact.post(axis, context)) * signs[axis];
			}
			return true;
		}

		double alpha = (time - before.time()) / (after.time() - before.time());
		Interpolation a = before.interpolation();
		Interpolation b = after.interpolation();
		if (a == Interpolation.LINEAR && (b == Interpolation.LINEAR || b == Interpolation.STEP)) {
			for (int axis = 0; axis < 3; axis++) {
				double from = before.post(axis, context);
				out[axis] = (from + (after.pre(axis, context) - from) * alpha) * signs[axis];
			}
		} else if (a == Interpolation.CATMULLROM || b == Interpolation.CATMULLROM) {
			Keyframe beforePlus = beforeIndex > 0 ? frames[beforeIndex - 1] : null;
			Keyframe afterPlus = afterIndex + 1 < frames.length ? frames[afterIndex + 1] : null;
			if (looping && frames.length >= 3) {
				if (beforePlus == null) {
					beforePlus = frames[frames.length - 2];
				}
				if (afterPlus == null) {
					afterPlus = frames[1];
				}
			}
			for (int axis = 0; axis < 3; axis++) {
				out[axis] = catmullRom(beforePlus, before, after, afterPlus, axis, alpha, context) * signs[axis];
			}
		} else {
			for (int axis = 0; axis < 3; axis++) {
				out[axis] = bezier(before, after, axis, alpha, context) * signs[axis];
			}
		}
		return true;
	}

	/** three.js {@code SplineCurve} through the neighbouring keyframes, as Blockbench uses it. */
	private static double catmullRom(Keyframe beforePlus, Keyframe before, Keyframe after, Keyframe afterPlus, int axis, double alpha, MolangContext context) {
		double[] ys = new double[4];
		int count = 0;
		boolean leading = beforePlus != null && !before.discontinuous();
		if (leading) {
			ys[count++] = beforePlus.post(axis, context);
		}
		ys[count++] = before.post(axis, context);
		ys[count++] = after.pre(axis, context);
		if (afterPlus != null && !after.discontinuous()) {
			ys[count++] = afterPlus.pre(axis, context);
		}
		double t = (alpha + (leading ? 1 : 0)) / (count - 1);
		double p = (count - 1) * t;
		int i = (int) Math.floor(p);
		double weight = p - i;
		double p0 = ys[i == 0 ? i : i - 1];
		double p1 = ys[Math.min(i, count - 1)];
		double p2 = ys[i > count - 2 ? count - 1 : i + 1];
		double p3 = ys[i > count - 3 ? count - 1 : i + 2];
		double v0 = (p2 - p0) * 0.5;
		double v1 = (p3 - p1) * 0.5;
		double t2 = weight * weight;
		double t3 = weight * t2;
		return (2 * p1 - 2 * p2 + v0 + v1) * t3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * t2 + v0 * weight + p1;
	}

	/** Cubic bezier between two keyframes using Blockbench's per-axis handles, solved for the curve's x = time. */
	private static double bezier(Keyframe before, Keyframe after, int axis, double alpha, MolangContext context) {
		double vBefore = before.post(axis, context);
		double vAfter = after.pre(axis, context);
		double gap = after.time() - before.time();
		double handleBefore = clamp(value(before.bezierRightTime(), axis), 0, gap);
		double handleAfter = clamp(value(after.bezierLeftTime(), axis), -gap, 0);
		double x0 = before.time();
		double x1 = before.time() + handleBefore;
		double x2 = after.time() + handleAfter;
		double x3 = after.time();
		double y0 = vBefore;
		double y1 = vBefore + value(before.bezierRightValue(), axis);
		double y2 = vAfter + value(after.bezierLeftValue(), axis);
		double y3 = vAfter;
		double target = x0 + gap * alpha;
		// x(s) is monotonic because the handles are clamped inside the gap, so bisection converges.
		double lo = 0;
		double hi = 1;
		for (int i = 0; i < 32; i++) {
			double mid = (lo + hi) / 2;
			if (cubic(x0, x1, x2, x3, mid) < target) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return cubic(y0, y1, y2, y3, (lo + hi) / 2);
	}

	private static double cubic(double p0, double p1, double p2, double p3, double s) {
		double inv = 1 - s;
		return inv * inv * inv * p0 + 3 * inv * inv * s * p1 + 3 * inv * s * s * p2 + s * s * s * p3;
	}

	private static double value(float[] values, int axis) {
		return values != null && axis < values.length ? values[axis] : 0;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}

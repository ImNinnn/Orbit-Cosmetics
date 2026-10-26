package dev.orbit.cosmetic.common.anim;

import java.util.Arrays;

/**
 * Per-bone animation offsets for one cosmetic, in Minecraft model space.
 * Rotation is added to the rest rotation (radians), position is added to the pivot (pixels), scale multiplies.
 *
 * <p>When several animations touch the same bone channel, the highest priority wins and equal priorities
 * combine (rotation/position add, scale multiplies), mirroring how Blockbench layers animations.
 */
public final class PoseBuffer {
	public static final int ROTATION = 0;
	public static final int POSITION = 1;
	public static final int SCALE = 2;

	private final int boneCount;
	public final float[] rotation;
	public final float[] position;
	public final float[] scale;
	private final int[] priority;
	private boolean empty = true;

	public PoseBuffer(int boneCount) {
		this.boneCount = boneCount;
		this.rotation = new float[boneCount * 3];
		this.position = new float[boneCount * 3];
		this.scale = new float[boneCount * 3];
		this.priority = new int[boneCount * 3];
		reset();
	}

	public int boneCount() {
		return boneCount;
	}

	/** @return true when no animation wrote anything since the last reset (the rest pose) */
	public boolean isEmpty() {
		return empty;
	}

	public void reset() {
		Arrays.fill(rotation, 0);
		Arrays.fill(position, 0);
		Arrays.fill(scale, 1);
		Arrays.fill(priority, Integer.MIN_VALUE);
		empty = true;
	}

	public void apply(int bone, int channel, double x, double y, double z, int priorityValue) {
		if (bone < 0 || bone >= boneCount) {
			return;
		}
		int slot = bone * 3 + channel;
		float[] target = switch (channel) {
			case ROTATION -> rotation;
			case POSITION -> position;
			default -> scale;
		};
		int base = bone * 3;
		int current = priority[slot];
		if (priorityValue > current) {
			priority[slot] = priorityValue;
			target[base] = (float) x;
			target[base + 1] = (float) y;
			target[base + 2] = (float) z;
		} else if (priorityValue == current) {
			if (channel == SCALE) {
				target[base] *= (float) x;
				target[base + 1] *= (float) y;
				target[base + 2] *= (float) z;
			} else {
				target[base] += (float) x;
				target[base + 1] += (float) y;
				target[base + 2] += (float) z;
			}
		} else {
			return;
		}
		empty = false;
	}

	/** Adds an offset on top of whatever animations produced, ignoring priorities (used by physics). */
	public void add(int bone, int channel, double x, double y, double z) {
		if (bone < 0 || bone >= boneCount || channel == SCALE) {
			return;
		}
		float[] target = channel == ROTATION ? rotation : position;
		int base = bone * 3;
		target[base] += (float) x;
		target[base + 1] += (float) y;
		target[base + 2] += (float) z;
		empty = false;
	}

	public void copyFrom(PoseBuffer other) {
		System.arraycopy(other.rotation, 0, rotation, 0, rotation.length);
		System.arraycopy(other.position, 0, position, 0, position.length);
		System.arraycopy(other.scale, 0, scale, 0, scale.length);
		System.arraycopy(other.priority, 0, priority, 0, priority.length);
		empty = other.empty;
	}
}

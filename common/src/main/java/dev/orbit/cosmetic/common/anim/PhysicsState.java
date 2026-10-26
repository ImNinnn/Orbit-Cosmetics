package dev.orbit.cosmetic.common.anim;

import java.util.List;

/**
 * Runs the {@link PhysicsLink}s of one worn cosmetic. Smoothing is applied once per game tick so the result
 * does not depend on the frame rate; frames interpolate between the last two ticks.
 */
public final class PhysicsState {
	/** Movement of the wearer during the last tick, relative to the body. */
	public record Input(double forward, double sideways, double vertical, double turn) {
		public static final Input REST = new Input(0, 0, 0, 0);

		/**
		 * @param dx world movement during one tick, in blocks
		 * @param bodyYaw body yaw in degrees (Minecraft convention: 0 faces +Z)
		 * @param yawDelta body yaw change during the tick, in degrees
		 */
		public static Input fromMovement(double dx, double dy, double dz, double bodyYaw, double yawDelta) {
			double yaw = Math.toRadians(bodyYaw);
			double forward = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
			double right = -dx * Math.cos(yaw) - dz * Math.sin(yaw);
			return new Input(forward * 20, right * 20, dy * 20, yawDelta * 20 / 90.0);
		}

		double value(PhysicsSource source) {
			return switch (source) {
				case NONE -> 0;
				case FORWARD -> forward;
				case SIDEWAYS -> sideways;
				case VERTICAL -> vertical;
				case SPEED -> Math.sqrt(forward * forward + sideways * sideways);
				case TURN -> turn;
			};
		}
	}

	private List<PhysicsLink> links = List.of();
	private float[] previous = new float[0];
	private float[] current = new float[0];

	public void setLinks(List<PhysicsLink> value) {
		if (value.equals(links)) {
			return;
		}
		links = List.copyOf(value);
		previous = new float[links.size() * PhysicsLink.AXES];
		current = new float[links.size() * PhysicsLink.AXES];
	}

	public boolean isEmpty() {
		return links.isEmpty();
	}

	public void tick(Input input) {
		System.arraycopy(current, 0, previous, 0, current.length);
		for (int l = 0; l < links.size(); l++) {
			List<AxisDrive> axes = links.get(l).axes();
			for (int a = 0; a < PhysicsLink.AXES; a++) {
				AxisDrive drive = axes.get(a);
				int slot = l * PhysicsLink.AXES + a;
				double target = drive.active() ? Math.max(-drive.max(), Math.min(drive.max(), input.value(drive.source()) * drive.strength())) : 0;
				current[slot] += (float) ((target - current[slot]) * (1 - drive.resistance()));
			}
		}
	}

	/**
	 * Adds the physics offsets, interpolated by {@code partial}, to the pose. Values are converted from
	 * Blockbench axes to model space like keyframes of format 5 files.
	 */
	public void apply(double partial, PoseBuffer pose) {
		float t = (float) Math.max(0, Math.min(1, partial));
		for (int l = 0; l < links.size(); l++) {
			int bone = links.get(l).bone();
			int o = l * PhysicsLink.AXES;
			float px = lerp(o, t);
			float py = lerp(o + 1, t);
			float pz = lerp(o + 2, t);
			float rx = lerp(o + 3, t);
			float ry = lerp(o + 4, t);
			float rz = lerp(o + 5, t);
			if (px != 0 || py != 0 || pz != 0) {
				pose.add(bone, PoseBuffer.POSITION, -px, -py, pz);
			}
			if (rx != 0 || ry != 0 || rz != 0) {
				pose.add(bone, PoseBuffer.ROTATION, Math.toRadians(-rx), Math.toRadians(-ry), Math.toRadians(rz));
			}
		}
	}

	/** Raw value of one axis after the last tick (Blockbench units), for tests and debugging. */
	public float value(int link, int axis) {
		return current[link * PhysicsLink.AXES + axis];
	}

	private float lerp(int slot, float t) {
		return previous[slot] + (current[slot] - previous[slot]) * t;
	}
}

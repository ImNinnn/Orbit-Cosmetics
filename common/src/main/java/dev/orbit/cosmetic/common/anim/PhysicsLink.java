package dev.orbit.cosmetic.common.anim;

import java.util.Arrays;
import java.util.List;

/**
 * Makes one part (bone) of a cosmetic react to the wearer's movement, like a cape lifting while running.
 * Axes are in Blockbench terms: position X, Y, Z (pixels) then rotation X, Y, Z (degrees).
 *
 * @param bone index into the model's bones
 */
public record PhysicsLink(int bone, List<AxisDrive> axes) {
	public static final int AXES = 6;
	public static final String[] AXIS_NAMES = {"position_x", "position_y", "position_z", "rotation_x", "rotation_y", "rotation_z"};

	public PhysicsLink {
		AxisDrive[] normalized = new AxisDrive[AXES];
		Arrays.fill(normalized, AxisDrive.OFF);
		if (axes != null) {
			for (int i = 0; i < Math.min(AXES, axes.size()); i++) {
				if (axes.get(i) != null) {
					normalized[i] = axes.get(i).clamped(i >= 3);
				}
			}
		}
		axes = List.of(normalized);
	}

	public static PhysicsLink empty(int bone) {
		return new PhysicsLink(bone, null);
	}

	public PhysicsLink withAxis(int axis, AxisDrive drive) {
		AxisDrive[] copy = axes.toArray(new AxisDrive[0]);
		copy[axis] = drive;
		return new PhysicsLink(bone, List.of(copy));
	}

	public boolean active() {
		return axes.stream().anyMatch(AxisDrive::active);
	}
}

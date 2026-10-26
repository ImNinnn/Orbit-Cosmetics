package dev.orbit.cosmetic.common.anim.molang;

import java.util.HashMap;
import java.util.Map;

/**
 * Values visible to Molang expressions while an animation is evaluated. Queries come from the wearer's
 * synchronized state, so every client computes the same result for the same player state.
 */
public final class MolangContext {
	public double animTime;
	public double lifeTime;
	public double groundSpeed;
	public double verticalSpeed;
	public double distanceMoved;
	public double moveSpeed;
	public double headXRotation;
	public double headYRotation;
	public boolean onGround;
	public boolean sneaking;
	public boolean sprinting;
	public boolean swimming;
	public boolean flying;
	public boolean inWater;

	final Map<String, Double> variables = new HashMap<>();

	public void resetVariables() {
		variables.clear();
	}

	double query(String name) {
		return switch (name) {
			case "anim_time" -> animTime;
			case "life_time" -> lifeTime;
			case "ground_speed" -> groundSpeed;
			case "vertical_speed" -> verticalSpeed;
			case "modified_distance_moved", "distance_moved", "walk_distance" -> distanceMoved;
			case "modified_move_speed" -> moveSpeed;
			case "head_x_rotation" -> headXRotation;
			case "head_y_rotation" -> headYRotation;
			case "is_on_ground" -> onGround ? 1 : 0;
			case "is_sneaking" -> sneaking ? 1 : 0;
			case "is_sprinting" -> sprinting ? 1 : 0;
			case "is_swimming" -> swimming ? 1 : 0;
			case "is_flying", "is_gliding" -> flying ? 1 : 0;
			case "is_in_water" -> inWater ? 1 : 0;
			case "is_moving" -> groundSpeed > 0.05 ? 1 : 0;
			default -> 0;
		};
	}
}

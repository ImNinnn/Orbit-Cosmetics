package dev.orbit.cosmetic.common.anim;

/**
 * Per-tick player state used to derive animation triggers. Everything here is either synchronized by
 * vanilla (position, on-ground, sprint/sneak/swim/elytra flags, pose) or by Orbit ({@code abilityFlying}).
 *
 * @param deltaY vertical movement since the last tick, in blocks
 * @param horizontalSpeed horizontal movement since the last tick, in blocks
 */
public record MotionInput(
	boolean onGround,
	double deltaY,
	double horizontalSpeed,
	boolean sprinting,
	boolean shiftDown,
	boolean crouchPose,
	boolean swimming,
	boolean fallFlying,
	boolean abilityFlying,
	boolean inWater,
	boolean passenger
) {
}

package dev.orbit.cosmetic.common.anim;

public enum Interpolation {
	LINEAR,
	STEP,
	CATMULLROM,
	BEZIER;

	public static Interpolation fromBlockbench(String name) {
		if (name == null) {
			return LINEAR;
		}
		return switch (name) {
			case "step" -> STEP;
			case "catmullrom", "smooth" -> CATMULLROM;
			case "bezier" -> BEZIER;
			default -> LINEAR;
		};
	}
}

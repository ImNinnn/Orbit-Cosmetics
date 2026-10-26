package dev.orbit.cosmetic.common.model;

/**
 * Body parts a cosmetic can attach to. The exact, case-sensitive Blockbench group names are the enum names
 * (except {@link #ROOT}, which is used for geometry outside every anchor group).
 *
 * <p>The pivot of each anchor is given in Blockbench coordinates (pixels, Y up, origin at the feet, player
 * facing north / -Z) and matches the vanilla wide-arm player model. See docs/COORDINATES.md.
 */
public enum Anchor {
	HEAD(0, 24, 0),
	BODY(0, 24, 0),
	LEFT_ARM(-5, 22, 0),
	RIGHT_ARM(5, 22, 0),
	LEFT_LEG(-1.9f, 12, 0),
	RIGHT_LEG(1.9f, 12, 0),
	/** Player-root geometry: follows the player's position and body rotation only. */
	ROOT(0, 24, 0);

	private static final Anchor[] VALUES = values();

	private final float pivotX;
	private final float pivotY;
	private final float pivotZ;

	Anchor(float pivotX, float pivotY, float pivotZ) {
		this.pivotX = pivotX;
		this.pivotY = pivotY;
		this.pivotZ = pivotZ;
	}

	/** Blockbench-space pivot of this anchor. */
	public float pivotX() {
		return pivotX;
	}

	public float pivotY() {
		return pivotY;
	}

	public float pivotZ() {
		return pivotZ;
	}

	public boolean isBodyPart() {
		return this != ROOT;
	}

	/** @return the anchor for an exact group name, or null. {@code ROOT} is not a valid group name. */
	public static Anchor fromGroupName(String name) {
		for (Anchor anchor : VALUES) {
			if (anchor != ROOT && anchor.name().equals(name)) {
				return anchor;
			}
		}
		return null;
	}

	public static Anchor byOrdinal(int ordinal) {
		return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
	}
}

package dev.orbit.cosmetic.common.model;

import java.util.List;

/**
 * A Blockbench group converted to Minecraft model space.
 *
 * @param index position in {@link CosmeticModel#bones()}; parents always come before their children
 * @param parent parent bone index, or -1 when the bone is attached directly to its anchor
 * @param anchor the body part this bone (and its subtree) follows
 * @param pivotX pivot relative to the parent's pivot, or to the anchor pivot for top-level bones (pixels)
 * @param rotX rest rotation in radians, applied in Z, Y, X order like {@code ModelPart}
 */
public record Bone(
	int index,
	String name,
	String uuid,
	int parent,
	Anchor anchor,
	float pivotX,
	float pivotY,
	float pivotZ,
	float rotX,
	float rotY,
	float rotZ,
	List<Quad> quads
) {
}

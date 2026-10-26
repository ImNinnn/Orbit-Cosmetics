package dev.orbit.cosmetic.common.anim;

import dev.orbit.cosmetic.common.anim.molang.MolangContext;
import dev.orbit.cosmetic.common.anim.molang.MolangExpression;

/**
 * A Blockbench keyframe for one channel. Values are in Blockbench's own axes; the owning {@link Channel}
 * converts them to Minecraft model space.
 *
 * @param pre value used when approaching this keyframe ({@code data_points[0]})
 * @param post value used when leaving this keyframe (the last data point; same as pre unless discontinuous)
 */
public record Keyframe(
	float time,
	MolangExpression[] pre,
	MolangExpression[] post,
	boolean discontinuous,
	Interpolation interpolation,
	float[] bezierLeftTime,
	float[] bezierLeftValue,
	float[] bezierRightTime,
	float[] bezierRightValue
) {
	public double pre(int axis, MolangContext context) {
		return pre[axis].eval(context);
	}

	public double post(int axis, MolangContext context) {
		return post[axis].eval(context);
	}
}

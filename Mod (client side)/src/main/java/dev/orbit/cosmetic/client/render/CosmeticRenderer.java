package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.orbit.cosmetic.common.anim.PoseBuffer;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.Bone;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws cosmetics as custom geometry. Bone matrices are computed when submitting (they are captured by the
 * geometry callback, which the renderer runs later in the frame), mirroring {@code ModelPart.translateAndRotate}:
 * translate to the pivot, rotate Z then Y then X, then scale.
 */
public final class CosmeticRenderer {
	private static final int WHITE = 0xFFFFFFFF;

	private CosmeticRenderer() {
	}

	/**
	 * @param onlyAnchor when not null, only bones of this anchor are drawn and the pose stack must already be
	 *                   positioned on that body part (used for first-person arms)
	 */
	public static void submit(List<RenderPiece> pieces, HumanoidModel<?> model, PoseStack poseStack, SubmitNodeCollector collector, int light, Anchor onlyAnchor) {
		for (RenderPiece piece : pieces) {
			BakedCosmetic baked = piece.baked();
			if (baked.isReleased()) {
				continue;
			}
			for (Anchor anchor : baked.drawnAnchors) {
				if (onlyAnchor != null) {
					if (anchor == onlyAnchor) {
						submitAnchor(baked, piece.pose(), anchor, poseStack, collector, light);
					}
					continue;
				}
				poseStack.pushPose();
				AnchorResolver.apply(model, anchor, poseStack);
				submitAnchor(baked, piece.pose(), anchor, poseStack, collector, light);
				poseStack.popPose();
			}
		}
	}

	private static void submitAnchor(BakedCosmetic baked, PoseBuffer pose, Anchor anchor, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		int[] bones = baked.bonesByAnchor.get(anchor);
		Matrix4f[] matrices = boneMatrices(baked.model().bones(), bones, pose);
		for (int texture = 0; texture < baked.textureCount(); texture++) {
			if (!usesTexture(baked, bones, texture)) {
				continue;
			}
			int t = texture;
			collector.submitCustomGeometry(poseStack, baked.renderType(texture), (posed, consumer) -> emit(baked, bones, matrices, t, posed, consumer, light));
		}
	}

	private static boolean usesTexture(BakedCosmetic baked, int[] bones, int texture) {
		for (int bone : bones) {
			if (baked.meshes[bone][texture] != null) {
				return true;
			}
		}
		return false;
	}

	static Matrix4f[] boneMatrices(List<Bone> allBones, int[] bones, PoseBuffer pose) {
		Matrix4f[] matrices = new Matrix4f[allBones.size()];
		boolean animated = pose != null && !pose.isEmpty();
		Quaternionf rotation = new Quaternionf();
		for (int index : bones) {
			Bone bone = allBones.get(index);
			Matrix4f matrix = bone.parent() >= 0 && matrices[bone.parent()] != null ? new Matrix4f(matrices[bone.parent()]) : new Matrix4f();
			float px = bone.pivotX();
			float py = bone.pivotY();
			float pz = bone.pivotZ();
			float rx = bone.rotX();
			float ry = bone.rotY();
			float rz = bone.rotZ();
			float sx = 1;
			float sy = 1;
			float sz = 1;
			if (animated) {
				int o = index * 3;
				px += pose.position[o];
				py += pose.position[o + 1];
				pz += pose.position[o + 2];
				rx += pose.rotation[o];
				ry += pose.rotation[o + 1];
				rz += pose.rotation[o + 2];
				sx = pose.scale[o];
				sy = pose.scale[o + 1];
				sz = pose.scale[o + 2];
			}
			matrix.translate(px / 16f, py / 16f, pz / 16f);
			if (rx != 0 || ry != 0 || rz != 0) {
				matrix.rotate(rotation.rotationZYX(rz, ry, rx));
			}
			if (sx != 1 || sy != 1 || sz != 1) {
				matrix.scale(sx, sy, sz);
			}
			matrices[index] = matrix;
		}
		return matrices;
	}

	private static void emit(BakedCosmetic baked, int[] bones, Matrix4f[] matrices, int texture, PoseStack.Pose posed, VertexConsumer consumer, int light) {
		Matrix4f position = new Matrix4f();
		Matrix3f normal = new Matrix3f();
		Matrix3f boneNormal = new Matrix3f();
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		for (int bone : bones) {
			float[] mesh = baked.meshes[bone][texture];
			if (mesh == null) {
				continue;
			}
			position.set(posed.pose()).mul(matrices[bone]);
			normal.set(posed.normal()).mul(boneNormal.set(matrices[bone]));
			for (int i = 0; i < mesh.length; i += BakedCosmetic.STRIDE) {
				position.transformPosition(mesh[i], mesh[i + 1], mesh[i + 2], p);
				normal.transform(mesh[i + 5], mesh[i + 6], mesh[i + 7], n);
				float length = n.lengthSquared();
				if (length > 1.0e-12f) {
					n.mul((float) (1 / Math.sqrt(length)));
				} else {
					n.set(0, 1, 0);
				}
				consumer.addVertex(p.x, p.y, p.z, WHITE, mesh[i + 3], mesh[i + 4], OverlayTexture.NO_OVERLAY, light, n.x, n.y, n.z);
			}
		}
	}
}

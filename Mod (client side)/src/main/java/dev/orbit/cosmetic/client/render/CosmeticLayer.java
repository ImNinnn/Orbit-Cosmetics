package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.config.ClientConfig;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * Player render layer that draws Orbit cosmetics. Layers run right after the player model's
 * {@code setupAnim}, so the parent model's parts hold this frame's (possibly mod-animated) pose.
 */
public final class CosmeticLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private HumanoidModel<AvatarRenderState> vanillaModel;

	public CosmeticLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		CosmeticRenderData data = state.getData(CosmeticRenderData.KEY);
		if (data == null || data.pieces().isEmpty() || state.isInvisible) {
			return;
		}
		HumanoidModel<?> model = getParentModel();
		if (OrbitClient.get().config().attachMode == ClientConfig.AttachMode.VANILLA) {
			HumanoidModel<AvatarRenderState> vanilla = vanillaModel();
			vanilla.setupAnim(state);
			model = vanilla;
		}
		CosmeticRenderer.submit(data.pieces(), model, poseStack, collector, light, null);
	}

	/** A private vanilla model, unaffected by model replacement mods, for the VANILLA attach mode. */
	private HumanoidModel<AvatarRenderState> vanillaModel() {
		if (vanillaModel == null) {
			vanillaModel = new HumanoidModel<>(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot());
		}
		return vanillaModel;
	}
}

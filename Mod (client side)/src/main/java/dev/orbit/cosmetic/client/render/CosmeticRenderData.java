package dev.orbit.cosmetic.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

import java.util.List;

/** Attached to a player's render state during extraction and consumed by {@link CosmeticLayer}. */
public record CosmeticRenderData(List<RenderPiece> pieces) {
	public static final RenderStateDataKey<CosmeticRenderData> KEY = RenderStateDataKey.create(() -> "orbit_cosmetic:cosmetics");
}

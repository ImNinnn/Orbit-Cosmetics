package dev.orbit.cosmetic.client.render;

import dev.orbit.cosmetic.common.anim.PoseBuffer;

/** One cosmetic to draw on one player this frame. {@code pose} may be null for the rest pose. */
public record RenderPiece(BakedCosmetic baked, PoseBuffer pose) {
}

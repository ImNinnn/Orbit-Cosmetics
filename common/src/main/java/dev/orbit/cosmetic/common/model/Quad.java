package dev.orbit.cosmetic.common.model;

/**
 * One textured quad in model space (pixels, Minecraft model axes: Y down, -Z forward), relative to the pivot
 * of the bone that owns it. Triangles are stored with the last vertex duplicated.
 *
 * @param positions x,y,z for 4 vertices (12 floats)
 * @param uvs u,v for 4 vertices, normalized to 0..1 (8 floats)
 * @param normal unit normal x,y,z
 * @param texture index into {@link CosmeticModel#textures()}
 */
public record Quad(float[] positions, float[] uvs, float[] normal, int texture) {
}

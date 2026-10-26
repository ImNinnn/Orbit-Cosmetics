package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.Quad;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A cosmetic ready to draw: textures uploaded to the GPU and geometry flattened into arrays.
 * Created and released on the render thread only.
 */
public final class BakedCosmetic {
	/** Floats per vertex in {@link #meshes}: x, y, z (blocks), u, v, nx, ny, nz. */
	static final int STRIDE = 8;

	private final AssetId id;
	private final CosmeticModel model;
	private final Identifier[] textureIds;
	private final RenderType[] renderTypes;
	/** [bone][texture] -> packed vertices, or null when the bone has no faces with that texture. */
	final float[][][] meshes;
	/** Bones per anchor, parents before children. */
	final Map<Anchor, int[]> bonesByAnchor = new EnumMap<>(Anchor.class);
	/** Anchors that have at least one face to draw (directly or through descendants). */
	final List<Anchor> drawnAnchors = new ArrayList<>();
	private boolean released;

	private BakedCosmetic(AssetId id, CosmeticModel model, Identifier[] textureIds, RenderType[] renderTypes) {
		this.id = id;
		this.model = model;
		this.textureIds = textureIds;
		this.renderTypes = renderTypes;
		List<Bone> bones = model.bones();
		this.meshes = new float[bones.size()][][];
		int textures = model.textures().size();
		Map<Anchor, List<Integer>> grouped = new EnumMap<>(Anchor.class);
		for (Bone bone : bones) {
			grouped.computeIfAbsent(bone.anchor(), a -> new ArrayList<>()).add(bone.index());
			int[] counts = new int[textures];
			for (Quad quad : bone.quads()) {
				counts[quad.texture()]++;
			}
			float[][] perTexture = new float[textures][];
			int[] fill = new int[textures];
			for (int t = 0; t < textures; t++) {
				if (counts[t] > 0) {
					perTexture[t] = new float[counts[t] * 4 * STRIDE];
				}
			}
			for (Quad quad : bone.quads()) {
				float[] target = perTexture[quad.texture()];
				int offset = fill[quad.texture()];
				for (int v = 0; v < 4; v++) {
					target[offset++] = quad.positions()[v * 3] / 16f;
					target[offset++] = quad.positions()[v * 3 + 1] / 16f;
					target[offset++] = quad.positions()[v * 3 + 2] / 16f;
					target[offset++] = quad.uvs()[v * 2];
					target[offset++] = quad.uvs()[v * 2 + 1];
					target[offset++] = quad.normal()[0];
					target[offset++] = quad.normal()[1];
					target[offset++] = quad.normal()[2];
				}
				fill[quad.texture()] = offset;
			}
			meshes[bone.index()] = perTexture;
		}
		for (Map.Entry<Anchor, List<Integer>> entry : grouped.entrySet()) {
			int[] indices = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
			bonesByAnchor.put(entry.getKey(), indices);
			boolean drawn = false;
			for (int index : indices) {
				if (!bones.get(index).quads().isEmpty()) {
					drawn = true;
					break;
				}
			}
			if (drawn) {
				drawnAnchors.add(entry.getKey());
			}
		}
	}

	/**
	 * Uploads the decoded images and builds the cosmetic. Takes ownership of the images.
	 * Must run on the render thread.
	 */
	static BakedCosmetic create(AssetId id, CosmeticModel model, NativeImage[] images, boolean[] translucent) {
		TextureManager textures = Minecraft.getInstance().getTextureManager();
		Identifier[] ids = new Identifier[images.length];
		RenderType[] types = new RenderType[images.length];
		for (int i = 0; i < images.length; i++) {
			ids[i] = Identifier.fromNamespaceAndPath(OrbitClient.MOD_ID, "cosmetic/" + id.hex() + "/" + i);
			String label = "Orbit cosmetic " + id.shortHex() + " #" + i;
			textures.register(ids[i], new DynamicTexture(() -> label, images[i]));
			types[i] = translucent[i] ? RenderTypes.entityTranslucent(ids[i]) : RenderTypes.entityCutout(ids[i]);
		}
		return new BakedCosmetic(id, model, ids, types);
	}

	public AssetId id() {
		return id;
	}

	public CosmeticModel model() {
		return model;
	}

	RenderType renderType(int texture) {
		return renderTypes[texture];
	}

	int textureCount() {
		return renderTypes.length;
	}

	public boolean isReleased() {
		return released;
	}

	/** Frees the GPU textures. Must run on the render thread. */
	void release() {
		if (released) {
			return;
		}
		released = true;
		TextureManager textures = Minecraft.getInstance().getTextureManager();
		for (Identifier textureId : textureIds) {
			textures.release(textureId);
		}
	}
}

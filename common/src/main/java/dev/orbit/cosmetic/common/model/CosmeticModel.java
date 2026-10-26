package dev.orbit.cosmetic.common.model;

import dev.orbit.cosmetic.common.anim.AnimationClip;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** A validated, render-ready cosmetic. Immutable and safe to share between players and threads. */
public record CosmeticModel(
	String name,
	List<Bone> bones,
	List<TextureData> textures,
	List<AnimationClip> animations,
	int quadCount
) {
	public Set<Anchor> anchorsUsed() {
		Set<Anchor> anchors = EnumSet.noneOf(Anchor.class);
		for (Bone bone : bones) {
			if (!bone.quads().isEmpty()) {
				anchors.add(bone.anchor());
			}
		}
		return anchors;
	}

	public boolean hasAnimations() {
		return !animations.isEmpty();
	}

	public int findBone(String uuid) {
		for (Bone bone : bones) {
			if (bone.uuid() != null && bone.uuid().equals(uuid)) {
				return bone.index();
			}
		}
		return -1;
	}
}

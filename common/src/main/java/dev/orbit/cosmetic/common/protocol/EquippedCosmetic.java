package dev.orbit.cosmetic.common.protocol;

import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.PhysicsLink;

import java.util.List;

/**
 * One cosmetic in a player's equipped configuration: which asset, how its animations are assigned, and which
 * parts react to movement.
 *
 * @param name display name supplied by the wearer; informational only and never trusted for identity
 * @param animations settings per animation index of the asset
 * @param physics movement-driven parts (see {@link PhysicsLink})
 */
public record EquippedCosmetic(AssetId asset, String name, List<AnimationSettings> animations, List<PhysicsLink> physics) {
	public EquippedCosmetic {
		animations = List.copyOf(animations);
		physics = physics == null ? List.of() : List.copyOf(physics);
	}

	public EquippedCosmetic(AssetId asset, String name, List<AnimationSettings> animations) {
		this(asset, name, animations, List.of());
	}
}

package dev.orbit.cosmetic.common.protocol;

import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.ModelLimits;

import java.util.EnumSet;
import java.util.Set;

/**
 * What the server allows for one particular player. The server is authoritative: the client uses this to
 * explain restrictions and avoid pointless uploads, but the server enforces every rule itself.
 *
 * @param enabled Orbit is enabled on this server at all
 * @param canWear this player may equip cosmetics (false when blocked or lacking permission)
 * @param allowedAnchors body parts cosmetics may use; models using others are rejected
 * @param message explanation shown when something is restricted (may be empty)
 */
public record ServerPolicy(
	boolean enabled,
	boolean canWear,
	boolean uploadsAllowed,
	boolean downloadsAllowed,
	boolean showNameplateIcons,
	boolean animationsAllowed,
	int maxEquipped,
	Set<Anchor> allowedAnchors,
	ModelLimits limits,
	String message
) {
	public ServerPolicy {
		allowedAnchors = allowedAnchors.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(allowedAnchors));
	}

	public static ServerPolicy disabled(String message) {
		return new ServerPolicy(false, false, false, false, false, false, 0, Set.of(), ModelLimits.DEFAULT, message);
	}
}

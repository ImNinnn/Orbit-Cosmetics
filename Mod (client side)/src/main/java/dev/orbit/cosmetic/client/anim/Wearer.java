package dev.orbit.cosmetic.client.anim;

import dev.orbit.cosmetic.client.render.BakedCosmetic;
import dev.orbit.cosmetic.client.render.RenderPiece;
import dev.orbit.cosmetic.common.anim.AnimationPlayer;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.MotionInput;
import dev.orbit.cosmetic.common.anim.MotionTracker;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.anim.PhysicsState;
import dev.orbit.cosmetic.common.anim.PoseBuffer;
import dev.orbit.cosmetic.common.anim.RandomSchedule;
import dev.orbit.cosmetic.common.anim.molang.MolangContext;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import dev.orbit.cosmetic.common.protocol.PlayerFlags;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Everything Orbit tracks for one player wearing cosmetics: what they wear, how their animations and physics
 * are configured, and the motion state that drives them. Times are ticks of the {@link dev.orbit.cosmetic.common.anim.AnimationClock}.
 */
public final class Wearer {
	/** One worn cosmetic. Animation state survives configuration updates for the same asset. */
	public static final class Worn {
		final AssetId asset;
		List<AnimationSettings> settings;
		List<PhysicsLink> physicsLinks;
		AnimationPlayer player;
		final PhysicsState physics = new PhysicsState();
		BakedCosmetic boundTo;
		PoseBuffer pose;

		Worn(AssetId asset, List<AnimationSettings> settings, List<PhysicsLink> physicsLinks) {
			this.asset = asset;
			this.settings = settings;
			this.physicsLinks = physicsLinks;
		}

		public AssetId asset() {
			return asset;
		}

		public List<PhysicsLink> physicsLinks() {
			return physicsLinks;
		}

		public PhysicsState physics() {
			return physics;
		}
	}

	public final UUID id;
	public boolean showIcon;
	public int flags;
	private final MotionTracker motion = new MotionTracker();
	private final MolangContext molang = new MolangContext();
	private List<Worn> worn = List.of();
	private double groundSpeed;
	private double verticalSpeed;
	private PhysicsState.Input movement = PhysicsState.Input.REST;
	private int clockGeneration;

	public Wearer(UUID id) {
		this.id = id;
	}

	public List<Worn> worn() {
		return worn;
	}

	/** Replaces the worn list, keeping animation and physics state of assets that are still worn. */
	public void setWorn(List<EquippedCosmetic> cosmetics) {
		Map<AssetId, Worn> previous = new HashMap<>();
		for (Worn w : worn) {
			previous.put(w.asset, w);
		}
		List<Worn> updated = new ArrayList<>(cosmetics.size());
		for (EquippedCosmetic cosmetic : cosmetics) {
			Worn existing = previous.get(cosmetic.asset());
			if (existing == null) {
				existing = new Worn(cosmetic.asset(), cosmetic.animations(), cosmetic.physics());
			} else {
				if (!existing.settings.equals(cosmetic.animations())) {
					existing.settings = cosmetic.animations();
					if (existing.player != null) {
						existing.player.setSettings(cosmetic.animations().toArray(new AnimationSettings[0]));
						existing.player.restart();
					}
				}
				existing.physicsLinks = cosmetic.physics();
			}
			updated.add(existing);
		}
		worn = List.copyOf(updated);
	}

	/**
	 * Advances motion state, animation triggers and physics by one game tick.
	 *
	 * @param time the animation clock's time, which follows the game time shared by every client
	 * @param generation the clock's generation; when it changes (new server or level), animations restart
	 */
	public void tick(Player player, double time, int generation, boolean localFlying, Function<AssetId, BakedCosmetic> lookup) {
		boolean newTimeline = generation != clockGeneration;
		clockGeneration = generation;
		double dx = player.getX() - player.xo;
		double dz = player.getZ() - player.zo;
		double dy = player.getY() - player.yo;
		groundSpeed = Math.sqrt(dx * dx + dz * dz);
		verticalSpeed = dy;
		movement = PhysicsState.Input.fromMovement(dx, dy, dz, player.yBodyRot, Mth.wrapDegrees(player.yBodyRot - player.yBodyRotO));
		boolean flying = localFlying || (flags & PlayerFlags.FLYING) != 0;
		motion.update(new MotionInput(
			player.onGround(),
			dy,
			groundSpeed,
			player.isSprinting(),
			player.isShiftKeyDown(),
			player.getPose() == Pose.CROUCHING,
			player.isVisuallySwimming(),
			player.isFallFlying(),
			flying,
			player.isInWater(),
			player.isPassenger()
		));
		for (Worn w : worn) {
			BakedCosmetic baked = lookup.apply(w.asset);
			if (baked == null) {
				continue;
			}
			if (w.boundTo != baked) {
				long seed = RandomSchedule.seed(id, w.asset.hex(), 0);
				w.player = baked.model().hasAnimations() ? new AnimationPlayer(baked.model().animations(), w.settings.toArray(new AnimationSettings[0]), seed) : null;
				w.pose = new PoseBuffer(baked.model().bones().size());
				w.boundTo = baked;
			}
			int bones = baked.model().bones().size();
			w.physics.setLinks(w.physicsLinks.stream().filter(link -> link.bone() < bones).toList());
			if (w.player != null) {
				if (newTimeline) {
					w.player.restart();
				}
				w.player.tick(time, motion);
			}
			w.physics.tick(movement);
		}
	}

	/**
	 * Builds this frame's render pieces.
	 *
	 * @param state the player's render state, or null when drawing first-person arms
	 * @param time animation clock time for this frame
	 * @param partialTick progress from the last tick to the next one (0-1)
	 * @param animate false to draw the rest pose (animations disabled, or the player is far away)
	 * @param faceBudget maximum faces to draw for this player
	 */
	public List<RenderPiece> pieces(AvatarRenderState state, double time, float partialTick, boolean animate, int faceBudget, Function<AssetId, BakedCosmetic> lookup) {
		List<RenderPiece> pieces = new ArrayList<>(worn.size());
		int faces = 0;
		boolean contextReady = false;
		for (Worn w : worn) {
			BakedCosmetic baked = lookup.apply(w.asset);
			if (baked == null) {
				continue;
			}
			faces += baked.model().quadCount();
			if (faces > faceBudget) {
				break;
			}
			PoseBuffer pose = null;
			if (animate && w.boundTo == baked && (w.player != null || !w.physics.isEmpty())) {
				if (w.player != null) {
					if (!contextReady) {
						fillContext(state, time);
						contextReady = true;
					}
					w.player.evaluate(time, molang, w.pose);
				} else {
					w.pose.reset();
				}
				w.physics.apply(partialTick, w.pose);
				pose = w.pose;
			}
			pieces.add(new RenderPiece(baked, pose));
		}
		return pieces;
	}

	private void fillContext(AvatarRenderState state, double time) {
		molang.lifeTime = time / 20.0;
		molang.groundSpeed = groundSpeed * 20;
		molang.verticalSpeed = verticalSpeed * 20;
		molang.onGround = motion.last() == null || motion.last().onGround();
		molang.sprinting = motion.last() != null && motion.last().sprinting();
		if (state == null) {
			return;
		}
		molang.distanceMoved = state.walkAnimationPos;
		molang.moveSpeed = state.walkAnimationSpeed;
		molang.headXRotation = state.xRot;
		molang.headYRotation = state.yRot;
		molang.sneaking = state.isCrouching;
		molang.swimming = state.isVisuallySwimming;
		molang.flying = state.isFallFlying || (flags & PlayerFlags.FLYING) != 0;
		molang.inWater = state.isInWater;
	}
}

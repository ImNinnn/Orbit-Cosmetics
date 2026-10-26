package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.orbit.cosmetic.client.mixin.ModelPartAccessor;
import dev.orbit.cosmetic.common.model.Anchor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Moves a pose stack onto a body part of the player model that is actually being rendered.
 *
 * <p>Vanilla and model replacement mods (Entity Model Features with packs such as Fresh Animations) pose the
 * {@code head}, {@code body}, arm and leg parts of {@link HumanoidModel} before render layers run, and EMF
 * applies its own pivots in {@link ModelPart#translateAndRotate}. Following the chain of parts from the
 * model root therefore makes cosmetics follow custom animations and proportions without depending on any
 * mod API. If a part cannot be found in the hierarchy (a renamed or detached part) the part's own transform
 * is applied directly, which is exactly the vanilla behaviour.
 */
public final class AnchorResolver {
	private static final Map<ModelPart, Map<ModelPart, List<ModelPart>>> PATHS = Collections.synchronizedMap(new WeakHashMap<>());

	private AnchorResolver() {
	}

	public static ModelPart part(HumanoidModel<?> model, Anchor anchor) {
		return switch (anchor) {
			case HEAD -> model.head;
			case BODY -> model.body;
			case LEFT_ARM -> model.leftArm;
			case RIGHT_ARM -> model.rightArm;
			case LEFT_LEG -> model.leftLeg;
			case RIGHT_LEG -> model.rightLeg;
			case ROOT -> model.root();
		};
	}

	/** Applies the transforms from the model root down to the anchor's part. */
	public static void apply(HumanoidModel<?> model, Anchor anchor, PoseStack poseStack) {
		ModelPart root = model.root();
		ModelPart target = part(model, anchor);
		if (target == root) {
			root.translateAndRotate(poseStack);
			return;
		}
		List<ModelPart> path = path(root, target);
		if (path == null) {
			root.translateAndRotate(poseStack);
			target.translateAndRotate(poseStack);
			return;
		}
		for (ModelPart part : path) {
			part.translateAndRotate(poseStack);
		}
	}

	private static List<ModelPart> path(ModelPart root, ModelPart target) {
		Map<ModelPart, List<ModelPart>> cache = PATHS.computeIfAbsent(root, r -> Collections.synchronizedMap(new WeakHashMap<>()));
		List<ModelPart> cached = cache.get(target);
		if (cached != null && isStillValid(cached)) {
			return cached;
		}
		List<ModelPart> found = new ArrayList<>();
		found.add(root);
		if (!search(root, target, found, 0)) {
			return null;
		}
		List<ModelPart> path = List.copyOf(found);
		cache.put(target, path);
		return path;
	}

	private static boolean search(ModelPart current, ModelPart target, List<ModelPart> path, int depth) {
		if (current == target) {
			return true;
		}
		if (depth > 32) {
			return false;
		}
		for (ModelPart child : ((ModelPartAccessor) (Object) current).orbit$children().values()) {
			path.add(child);
			if (search(child, target, path, depth + 1)) {
				return true;
			}
			path.removeLast();
		}
		return false;
	}

	/** Model replacement mods can rebuild hierarchies on resource reload; verify each link still exists. */
	private static boolean isStillValid(List<ModelPart> path) {
		for (int i = 0; i + 1 < path.size(); i++) {
			if (!((ModelPartAccessor) (Object) path.get(i)).orbit$children().containsValue(path.get(i + 1))) {
				return false;
			}
		}
		return true;
	}
}

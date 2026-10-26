package dev.orbit.cosmetic.common.anim;

import dev.orbit.cosmetic.common.anim.molang.MolangContext;
import dev.orbit.cosmetic.common.anim.molang.MolangExpression;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnimationTest {
	private static final float[] SAME = {1, 1, 1};

	private static Keyframe key(float time, double value, Interpolation interpolation) {
		MolangExpression[] values = {MolangExpression.constant(value), MolangExpression.constant(0), MolangExpression.constant(0)};
		return new Keyframe(time, values, values, false, interpolation, null, null, null, null);
	}

	private static double sample(Channel channel, double time, boolean loop) {
		double[] out = new double[3];
		assertTrue(channel.sample(time, loop, new MolangContext(), out));
		return out[0];
	}

	@Test
	void linearAndClampedEnds() {
		Channel channel = new Channel(new Keyframe[] {key(0, 0, Interpolation.LINEAR), key(1, 10, Interpolation.LINEAR)}, SAME);
		assertEquals(0, sample(channel, -1, false));
		assertEquals(5, sample(channel, 0.5, false), 1e-9);
		assertEquals(10, sample(channel, 2, false));
	}

	@Test
	void stepHoldsPreviousValue() {
		Channel channel = new Channel(new Keyframe[] {key(0, 3, Interpolation.STEP), key(1, 10, Interpolation.LINEAR)}, SAME);
		assertEquals(3, sample(channel, 0.99, false));
		assertEquals(10, sample(channel, 1, false));
	}

	@Test
	void discontinuousKeyframesUsePreAndPost() {
		MolangExpression[] pre = {MolangExpression.constant(5), MolangExpression.constant(0), MolangExpression.constant(0)};
		MolangExpression[] post = {MolangExpression.constant(-5), MolangExpression.constant(0), MolangExpression.constant(0)};
		Keyframe middle = new Keyframe(1, pre, post, true, Interpolation.LINEAR, null, null, null, null);
		Channel channel = new Channel(new Keyframe[] {key(0, 0, Interpolation.LINEAR), middle, key(2, 0, Interpolation.LINEAR)}, SAME);
		assertEquals(2.5, sample(channel, 0.5, false), 1e-9, "approaching uses the pre value");
		assertEquals(-2.5, sample(channel, 1.5, false), 1e-9, "leaving uses the post value");
	}

	@Test
	void catmullRomPassesThroughKeyframesSmoothly() {
		Channel channel = new Channel(new Keyframe[] {
			key(0, 0, Interpolation.CATMULLROM), key(1, 10, Interpolation.CATMULLROM), key(2, 0, Interpolation.CATMULLROM)
		}, SAME);
		assertEquals(10, sample(channel, 1, false), 1e-9);
		// Uniform Catmull-Rom between 0 and 10 with neighbours (0 duplicated, 0): p(0.5) = 5.625
		assertEquals(5.625, sample(channel, 0.5, false), 1e-9);
		assertEquals(5.625, sample(channel, 1.5, false), 1e-9);
	}

	@Test
	void bezierWithFlatHandlesIsLinear() {
		MolangExpression[] a = {MolangExpression.constant(0), MolangExpression.constant(0), MolangExpression.constant(0)};
		MolangExpression[] b = {MolangExpression.constant(10), MolangExpression.constant(0), MolangExpression.constant(0)};
		float[] zero = {0, 0, 0};
		Keyframe first = new Keyframe(0, a, a, false, Interpolation.BEZIER, zero, zero, zero, zero);
		Keyframe second = new Keyframe(1, b, b, false, Interpolation.BEZIER, zero, zero, zero, zero);
		Channel channel = new Channel(new Keyframe[] {first, second}, SAME);
		assertEquals(2.5, sample(channel, 0.25, false), 1e-6);
		assertEquals(7.5, sample(channel, 0.75, false), 1e-6);
	}

	@Test
	void signsConvertAxes() {
		Channel channel = new Channel(new Keyframe[] {key(0, 4, Interpolation.LINEAR)}, new float[] {-1, 1, 1});
		assertEquals(-4, sample(channel, 0, false));
	}

	@Test
	void clipTimeHonoursLoopModes() {
		AnimationClip clip = new AnimationClip("x", LoopMode.LOOP, 2, 0, 0, List.of());
		assertEquals(0.5, clip.clipTime(2.5, LoopMode.LOOP), 1e-9);
		assertEquals(2, clip.clipTime(5, LoopMode.HOLD), 1e-9);
		assertTrue(clip.isFinished(2.1, LoopMode.ONCE));
		assertFalse(clip.isFinished(1.9, LoopMode.ONCE));
	}

	@Test
	void triggerSuggestions() {
		assertEquals(AnimationTrigger.SPRINT, AnimationTrigger.suggest("SPRINT"));
		assertEquals(AnimationTrigger.WALK, AnimationTrigger.suggest("animation.top_hat.walk"));
		assertEquals(AnimationTrigger.SPRINT, AnimationTrigger.suggest("run_cycle"));
		assertEquals(AnimationTrigger.IDLE, AnimationTrigger.suggest("idle"));
		assertEquals(AnimationTrigger.FLY, AnimationTrigger.suggest("Elytra Glide"));
		assertEquals(AnimationTrigger.NONE, AnimationTrigger.suggest("wiggle"));
		AnimationSettings jump = AnimationSettings.defaultsFor(new AnimationClip("JUMP", LoopMode.LOOP, 1, 0, 0, List.of()));
		assertEquals(AnimationTrigger.JUMP, jump.trigger());
		assertEquals(LoopMode.ONCE, jump.loop(), "a jump plays once by default");
		assertEquals(AnimationTrigger.JUMP.defaultPriority(), jump.priority());
	}

	private static MotionInput input(boolean onGround, double dy, double speed, boolean sprint) {
		return new MotionInput(onGround, dy, speed, sprint, false, false, false, false, false, false, false);
	}

	@Test
	void motionTrackerDerivesStates() {
		MotionTracker tracker = new MotionTracker();
		tracker.update(input(true, 0, 0, false));
		assertTrue(tracker.isActive(AnimationTrigger.IDLE));
		assertTrue(tracker.isActive(AnimationTrigger.ALWAYS));
		assertFalse(tracker.isActive(AnimationTrigger.WALK));

		tracker.update(input(true, 0, 0.2, false));
		assertTrue(tracker.isActive(AnimationTrigger.WALK));
		assertFalse(tracker.isActive(AnimationTrigger.IDLE));
		assertTrue((tracker.started() & AnimationTrigger.WALK.bit()) != 0);

		tracker.update(input(true, 0, 0.28, true));
		assertTrue(tracker.isActive(AnimationTrigger.SPRINT));
		assertFalse(tracker.isActive(AnimationTrigger.WALK));

		tracker.update(input(false, 0.42, 0.28, true));
		assertTrue(tracker.isActive(AnimationTrigger.JUMP));
		assertTrue((tracker.started() & AnimationTrigger.JUMP.bit()) != 0);

		tracker.update(input(false, 0.3, 0.28, true));
		assertTrue(tracker.isActive(AnimationTrigger.JUMP));
		assertEquals(0, tracker.started() & AnimationTrigger.JUMP.bit(), "the jump starts only once");

		tracker.update(input(false, -0.3, 0.28, true));
		assertTrue(tracker.isActive(AnimationTrigger.FALL));

		tracker.update(input(true, 0, 0, false));
		assertFalse(tracker.isActive(AnimationTrigger.JUMP));
		assertFalse(tracker.isActive(AnimationTrigger.FALL));
		assertTrue(tracker.isActive(AnimationTrigger.IDLE));

		tracker.update(new MotionInput(false, -0.5, 0, false, false, false, false, false, true, false, false));
		assertTrue(tracker.isActive(AnimationTrigger.FLY));
		assertFalse(tracker.isActive(AnimationTrigger.FALL), "flying players are not falling");

		tracker.update(new MotionInput(true, 0, 0, false, true, true, false, false, false, false, false));
		assertTrue(tracker.isActive(AnimationTrigger.SNEAK));
		assertTrue(tracker.isActive(AnimationTrigger.CROUCH));
	}

	private static AnimationClip clip(String name, int bone, double value, float length, LoopMode loop) {
		Keyframe k = key(0, value, Interpolation.LINEAR);
		Channel rotation = new Channel(new Keyframe[] {k}, SAME);
		return new AnimationClip(name, loop, length, 0, 0, List.of(new BoneTrack(bone, rotation, null, null)));
	}

	@Test
	void playerAppliesPriorityAndInterrupt() {
		AnimationClip walk = clip("WALK", 0, 10, 1, LoopMode.LOOP);
		AnimationClip always = clip("spin", 0, 1, 1, LoopMode.LOOP);
		AnimationClip jump = clip("JUMP", 1, 5, 0.5f, LoopMode.ONCE);
		AnimationSettings[] settings = {
			AnimationSettings.defaultsFor(walk),
			new AnimationSettings(AnimationTrigger.ALWAYS, LoopMode.LOOP, 1, 20, false),
			AnimationSettings.defaultsFor(jump).withInterrupt(true),
		};
		AnimationPlayer player = new AnimationPlayer(List.of(walk, always, jump), settings);
		PoseBuffer pose = new PoseBuffer(2);
		MotionTracker tracker = new MotionTracker();

		tracker.update(input(true, 0, 0.2, false));
		player.tick(0, tracker);
		player.evaluate(1, new MolangContext(), pose);
		// WALK and the ALWAYS animation share priority 20 on bone 0, so they add up.
		assertEquals(Math.toRadians(11), pose.rotation[0], 1e-6);

		tracker.update(input(false, 0.42, 0.2, false));
		player.tick(1, tracker);
		player.evaluate(2, new MolangContext(), pose);
		// JUMP (priority 70, interrupt) suppresses everything below it.
		assertEquals(0, pose.rotation[0], 1e-6);
		assertEquals(Math.toRadians(5), pose.rotation[3], 1e-6);

		// After 0.5 s the ONCE jump has finished and the default pose returns.
		tracker.update(input(false, 0.1, 0.2, false));
		player.tick(15, tracker);
		player.evaluate(15, new MolangContext(), pose);
		assertEquals(0, pose.rotation[3], 1e-6);
		assertEquals(Math.toRadians(1), pose.rotation[0], 1e-6, "only the ALWAYS animation remains while airborne");
	}

	@Test
	void evaluationIsDeterministic() {
		AnimationClip clip = clip("IDLE", 0, 3, 2, LoopMode.LOOP);
		PoseBuffer a = new PoseBuffer(1);
		PoseBuffer b = new PoseBuffer(1);
		for (PoseBuffer target : new PoseBuffer[] {a, b}) {
			AnimationPlayer player = new AnimationPlayer(List.of(clip), null);
			MotionTracker tracker = new MotionTracker();
			tracker.update(input(true, 0, 0, false));
			player.tick(100, tracker);
			player.evaluate(137.25, new MolangContext(), target);
		}
		assertArrayEquals(a.rotation, b.rotation);
	}
}

package dev.orbit.cosmetic.common.anim;

import dev.orbit.cosmetic.common.anim.molang.MolangContext;
import dev.orbit.cosmetic.common.anim.molang.MolangExpression;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RandomAndPhysicsTest {
	private static List<double[]> plays(RandomSchedule schedule, long from, long to) {
		List<double[]> result = new ArrayList<>();
		double current = Double.NaN;
		for (long t = from; t < to; t++) {
			double start = schedule.activeStart(t);
			if (!Double.isNaN(start) && start != current) {
				result.add(new double[] {start});
				current = start;
			}
		}
		return result;
	}

	@Test
	void randomScheduleRespectsWaitRange() {
		// 2 s plays, 5 to 15 s waits.
		RandomSchedule schedule = new RandomSchedule(42, 40, 100, 300);
		List<double[]> starts = plays(schedule, 0, 20000);
		assertTrue(starts.size() > 40, "plays " + starts.size());
		for (int i = 1; i < starts.size(); i++) {
			double wait = starts.get(i)[0] - starts.get(i - 1)[0] - 40;
			assertTrue(wait >= 100 - 1e-9 && wait <= 300 + 1e-9, "wait " + wait);
		}
	}

	@Test
	void everyClientComputesTheSamePlayTimes() {
		UUID wearer = UUID.randomUUID();
		long seed = RandomSchedule.seed(wearer, "ab".repeat(32), 0);
		RandomSchedule early = new RandomSchedule(seed, 30, 20, 200);
		RandomSchedule lateJoiner = new RandomSchedule(seed, 30, 20, 200);
		List<double[]> a = plays(early, 50_000, 60_000);
		// The second client only starts looking much later and in big jumps; it must still agree.
		List<Double> b = new ArrayList<>();
		for (double[] start : a) {
			b.add(lateJoiner.activeStart(start[0] + 5));
		}
		for (int i = 0; i < a.size(); i++) {
			assertEquals(a.get(i)[0], b.get(i), 1e-9);
		}
		RandomSchedule otherAnimation = new RandomSchedule(RandomSchedule.seed(wearer, "ab".repeat(32), 1), 30, 20, 200);
		assertNotEquals(a.getFirst()[0], plays(otherAnimation, 50_000, 60_000).getFirst()[0], "different animations are not synchronized with each other");
	}

	@Test
	void randomTriggerPlaysOnceThenWaits() {
		MolangExpression[] value = {MolangExpression.constant(10), MolangExpression.constant(0), MolangExpression.constant(0)};
		Channel channel = new Channel(new Keyframe[] {new Keyframe(0, value, value, false, Interpolation.LINEAR, null, null, null, null)}, new float[] {1, 1, 1});
		AnimationClip clip = new AnimationClip("fidget", LoopMode.LOOP, 1, 0, 0, List.of(new BoneTrack(0, channel, null, null)));
		AnimationSettings settings = new AnimationSettings(AnimationTrigger.RANDOM, LoopMode.LOOP, 1, 15, false, 3, 3);
		AnimationPlayer player = new AnimationPlayer(List.of(clip), new AnimationSettings[] {settings}, 7);
		MotionTracker motion = new MotionTracker();
		motion.update(new MotionInput(true, 0, 0, false, false, false, false, false, false, false, false));
		PoseBuffer pose = new PoseBuffer(1);
		int playingTicks = 0;
		for (long t = 0; t < 24000; t++) {
			player.tick(t, motion);
			player.evaluate(t, new MolangContext(), pose);
			if (!pose.isEmpty()) {
				playingTicks++;
			}
		}
		// Each cycle is a 20 tick play and a 60 tick wait, even though the clip itself is marked as looping.
		assertEquals(24000 / 80.0 * 20, playingTicks, 40);
	}

	@Test
	void physicsFollowsMovementWithLimitsAndResistance() {
		AxisDrive capeLift = new AxisDrive(PhysicsSource.FORWARD, 10, 60, 0.5f);
		PhysicsLink link = PhysicsLink.empty(0).withAxis(3, capeLift);
		PhysicsState state = new PhysicsState();
		state.setLinks(List.of(link));

		// Sprinting forward at 5.6 blocks/s would ask for 56 degrees; resistance 0.5 halves the gap each tick.
		PhysicsState.Input sprint = new PhysicsState.Input(5.6, 0, 0, 0);
		state.tick(sprint);
		assertEquals(28, state.value(0, 3), 1e-4);
		for (int i = 0; i < 40; i++) {
			state.tick(sprint);
		}
		assertEquals(56, state.value(0, 3), 1e-3);

		// Faster than the limit is clamped.
		for (int i = 0; i < 40; i++) {
			state.tick(new PhysicsState.Input(20, 0, 0, 0));
		}
		assertEquals(60, state.value(0, 3), 1e-3);

		// Standing still settles back to rest.
		for (int i = 0; i < 60; i++) {
			state.tick(PhysicsState.Input.REST);
		}
		assertEquals(0, state.value(0, 3), 1e-3);

		PoseBuffer pose = new PoseBuffer(1);
		state.tick(sprint);
		state.apply(1, pose);
		// Blockbench rotation X maps to model space -X.
		assertEquals(Math.toRadians(-28), pose.rotation[0], 1e-4);
	}

	@Test
	void movementIsRelativeToTheBody() {
		// Facing south (yaw 0) and moving +Z is forward; moving -X is to the player's right.
		PhysicsState.Input forward = PhysicsState.Input.fromMovement(0, 0, 0.1, 0, 0);
		assertEquals(2, forward.forward(), 1e-9);
		assertEquals(0, forward.sideways(), 1e-9);
		PhysicsState.Input right = PhysicsState.Input.fromMovement(-0.1, 0, 0, 0, 0);
		assertEquals(2, right.sideways(), 1e-9);
		// Facing west (yaw 90) the forward direction is -X.
		PhysicsState.Input west = PhysicsState.Input.fromMovement(-0.1, 0, 0, 90, 0);
		assertEquals(2, west.forward(), 1e-9);
		assertEquals(1, PhysicsState.Input.fromMovement(0, 0, 0, 0, 4.5).turn(), 1e-9, "4.5 degrees per tick is a quarter turn per second");
	}

	@Test
	void driveValuesAreSanitized() {
		AxisDrive drive = new AxisDrive(PhysicsSource.SPEED, Float.NaN, -5, 7).clamped(false);
		assertEquals(0, drive.strength());
		assertEquals(0, drive.max());
		assertEquals(AxisDrive.MAX_RESISTANCE, drive.resistance());
		assertEquals(AxisDrive.MAX_ROTATION_STRENGTH, new AxisDrive(PhysicsSource.SPEED, 1000, 1000, 0).clamped(true).strength());
		AnimationSettings waits = new AnimationSettings(AnimationTrigger.RANDOM, LoopMode.ONCE, 1, 15, false, 30, 10);
		assertEquals(30, waits.maxWait(), "max wait is never below min wait");
	}
}

package dev.orbit.cosmetic.common.anim;

import dev.orbit.cosmetic.common.anim.molang.MolangContext;
import dev.orbit.cosmetic.common.anim.molang.MolangExpression;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnimationClockTest {
	/**
	 * What the client sees on a server running at {@code tps}: its own count goes up every tick and is reset to
	 * the server's time whenever a time packet arrives (every 20 server ticks).
	 */
	private static long[] laggingGameTime(long start, int clientTicks, double tps) {
		long[] result = new long[clientTicks];
		long local = start;
		double server = start;
		double nextPacket = start + 20;
		for (int i = 0; i < clientTicks; i++) {
			local++;
			server += tps / 20.0;
			if (server >= nextPacket) {
				local = (long) nextPacket;
				nextPacket += 20;
			}
			result[i] = local;
		}
		return result;
	}

	@Test
	void neverStepsBackOnALaggingServer() {
		AnimationClock clock = new AnimationClock();
		long[] times = laggingGameTime(1_000_000, 2000, 14);
		clock.tick(times[0]);
		int generation = clock.generation();
		double previous = clock.time();
		double slowest = Double.MAX_VALUE;
		double fastest = 0;
		for (int i = 1; i < times.length; i++) {
			clock.tick(times[i]);
			double step = clock.time() - previous;
			previous = clock.time();
			assertTrue(step >= 0, "clock went back at tick " + i);
			if (i > 400) {
				slowest = Math.min(slowest, step);
				fastest = Math.max(fastest, step);
			}
			// Raw game time jumps back by up to 6 ticks here; the clock stays close to it.
			assertEquals(times[i], clock.time(), 30, "drifted at tick " + i);
		}
		assertEquals(generation, clock.generation(), "lag must not count as a new timeline");
		// Once settled it runs at the server's pace (0.7) with only a small wobble.
		assertTrue(slowest > 0.55 && fastest < 0.85, "step between " + slowest + " and " + fastest);
	}

	@Test
	void followsAHealthyServerExactly() {
		AnimationClock clock = new AnimationClock();
		for (long t = 500; t < 1500; t++) {
			clock.tick(t);
			assertEquals(t, clock.time(), 1e-9);
		}
		AnimationClock frames = new AnimationClock();
		frames.tick(500);
		frames.tick(501);
		assertEquals(500.5, frames.at(0.5f), 1e-9);
	}

	@Test
	void standsStillWhileTheLevelIsPaused() {
		AnimationClock clock = new AnimationClock();
		clock.tick(100);
		clock.tick(101);
		for (int i = 0; i < 50; i++) {
			clock.tick(101);
		}
		assertEquals(101, clock.time(), 1e-9);
	}

	@Test
	void jumpsToANewTimelineAndCountsIt() {
		AnimationClock clock = new AnimationClock();
		clock.tick(5_000_000);
		int first = clock.generation();
		// Subserver switch: the new level starts at 0, then the new server's time arrives.
		clock.tick(1);
		assertEquals(1, clock.time(), 1e-9);
		clock.tick(2_000_000);
		assertEquals(2_000_000, clock.time(), 1e-9);
		assertEquals(first + 2, clock.generation());
		clock.reset();
		clock.tick(2_000_001);
		assertEquals(first + 3, clock.generation());
	}

	@Test
	void alwaysAnimationRecoversWhenTimeGoesBack() {
		MolangExpression[] value = {MolangExpression.constant(10), MolangExpression.constant(0), MolangExpression.constant(0)};
		Channel channel = new Channel(new Keyframe[] {new Keyframe(0, value, value, false, Interpolation.LINEAR, null, null, null, null)}, new float[] {1, 1, 1});
		// A one second clip, played once whenever "always" (re)starts.
		AnimationClip clip = new AnimationClip("always", LoopMode.ONCE, 1, 0, 0, List.of(new BoneTrack(0, channel, null, null)));
		AnimationSettings always = new AnimationSettings(AnimationTrigger.ALWAYS, LoopMode.ONCE, 1, 0, false);
		AnimationPlayer player = new AnimationPlayer(List.of(clip), new AnimationSettings[] {always});
		MotionTracker tracker = new MotionTracker();
		tracker.update(new MotionInput(true, 0, 0, false, false, false, false, false, false, false, false));
		PoseBuffer pose = new PoseBuffer(1);

		player.tick(5_000_000, tracker);
		player.evaluate(5_000_010, new MolangContext(), pose);
		assertFalse(pose.isEmpty(), "plays on the first server");
		// The next server's time is much lower. With the old start time kept, the clip would sit on its first
		// frame until the new time caught up with the old one.
		player.tick(1_000, tracker);
		player.evaluate(1_010, new MolangContext(), pose);
		assertFalse(pose.isEmpty(), "plays again on the second server");
		player.evaluate(1_030, new MolangContext(), pose);
		assertTrue(pose.isEmpty(), "and finishes after one second there");
	}
}

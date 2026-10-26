package dev.orbit.cosmetic.common.anim;

/**
 * Smooth animation time that follows the shared game time.
 *
 * <p>The client counts the level's game time up by itself every tick and overwrites it with every time packet
 * from the server. When the server runs below 20 ticks per second (common on large servers), the client's count
 * runs ahead and is pulled back once a second, so animations driven by it directly skip backwards. This clock
 * instead advances one tick per tick and is nudged towards the game time a little at a time: it never jumps
 * for small differences and stays close enough to the shared time that random animations still line up on
 * every client.
 *
 * <p>Large differences are real changes of time, such as joining a server or switching subservers behind a
 * proxy (a new level starts at time 0 until the server sends its time). The clock then jumps to the game time
 * and increments {@link #generation()}, so animations can restart on the new timeline.
 */
public final class AnimationClock {
	/** Differences larger than this (10 seconds) are taken as a new timeline. */
	public static final double RESYNC_TICKS = 200;
	/** Share of the remaining difference corrected per tick. */
	static final double FOLLOW = 0.02;
	/** Fastest catch-up speed, in ticks per tick. */
	static final double MAX_STEP = 3;

	private double time;
	private double previous;
	private long lastGameTime;
	private boolean started;
	private int generation;

	/**
	 * Advances one client tick.
	 *
	 * @param gameTime the level's game time after this tick
	 */
	public void tick(long gameTime) {
		if (!started || Math.abs(gameTime - time) > RESYNC_TICKS) {
			time = gameTime;
			previous = gameTime;
			lastGameTime = gameTime;
			started = true;
			generation++;
			return;
		}
		previous = time;
		// Paused or frozen levels do not advance their time; neither does the clock, apart from catching up.
		double step = gameTime != lastGameTime ? 1 : 0;
		lastGameTime = gameTime;
		time += Math.max(0, Math.min(MAX_STEP, step + (gameTime - (time + step)) * FOLLOW));
	}

	/** Forgets the timeline; the next {@link #tick} jumps to the game time and starts a new generation. */
	public void reset() {
		started = false;
	}

	/** Time at the last tick. */
	public double time() {
		return time;
	}

	/** Time between the last two ticks, for rendering a frame. */
	public double at(float partialTick) {
		return previous + (time - previous) * partialTick;
	}

	/** Changes whenever the clock jumped to a new timeline. */
	public int generation() {
		return generation;
	}
}

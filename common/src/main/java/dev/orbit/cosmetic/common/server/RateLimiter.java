package dev.orbit.cosmetic.common.server;

/** Token bucket. Not thread-safe; each instance belongs to one player session on the main thread. */
public final class RateLimiter {
	private final double capacity;
	private final double refillPerMilli;
	private double tokens;
	private long last;

	public RateLimiter(double capacity, double refillPerSecond, long now) {
		this.capacity = capacity;
		this.refillPerMilli = refillPerSecond / 1000.0;
		this.tokens = capacity;
		this.last = now;
	}

	public boolean tryAcquire(double amount, long now) {
		if (now > last) {
			tokens = Math.min(capacity, tokens + (now - last) * refillPerMilli);
			last = now;
		}
		if (tokens >= amount) {
			tokens -= amount;
			return true;
		}
		return false;
	}
}

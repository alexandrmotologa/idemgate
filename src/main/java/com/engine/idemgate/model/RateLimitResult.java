package com.engine.idemgate.model;

/**
 * Result of evaluating a request against token bucket rate limit rules.
 */
public class RateLimitResult {

    private final boolean allowed;
    private final long limit;
    private final long remaining;
    private final long resetSeconds;
    private final long retryAfterSeconds;

    public RateLimitResult(boolean allowed, long limit, long remaining, long resetSeconds, long retryAfterSeconds) {
        this.allowed = allowed;
        this.limit = limit;
        this.remaining = remaining;
        this.resetSeconds = resetSeconds;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static RateLimitResult allow(long limit, long remaining, long resetSeconds) {
        return new RateLimitResult(true, limit, remaining, resetSeconds, 0);
    }

    public static RateLimitResult reject(long limit, long resetSeconds, long retryAfterSeconds) {
        return new RateLimitResult(false, limit, 0, resetSeconds, retryAfterSeconds);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public long getLimit() {
        return limit;
    }

    public long getRemaining() {
        return remaining;
    }

    public long getResetSeconds() {
        return resetSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

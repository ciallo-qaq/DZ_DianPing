package com.dzdp.ratelimit;

/**
 * 单次限流判定结果。
 */
public final class RateLimitResult {

    private final boolean allowed;
    private final long remaining;
    private final long retryAfterMillis;

    private RateLimitResult(boolean allowed, long remaining, long retryAfterMillis) {
        this.allowed = allowed;
        this.remaining = remaining;
        this.retryAfterMillis = retryAfterMillis;
    }

    public static RateLimitResult allowed(long remaining) {
        return new RateLimitResult(true, remaining, 0L);
    }

    public static RateLimitResult denied(long remaining, long retryAfterMillis) {
        return new RateLimitResult(false, remaining, Math.max(retryAfterMillis, 0L));
    }

    public boolean isAllowed() {
        return allowed;
    }

    public long getRemaining() {
        return remaining;
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    @Override
    public String toString() {
        return "RateLimitResult{allowed=" + allowed + ", remaining=" + remaining
                + ", retryAfterMillis=" + retryAfterMillis + '}';
    }
}

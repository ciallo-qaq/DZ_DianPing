package com.dzdp.ratelimit;

/**
 * 被限流时抛出，由 WebExceptionAdvice 统一转换成 HTTP 429 + Retry-After。
 */
public class RateLimitException extends RuntimeException {

    private final long retryAfterMillis;

    public RateLimitException(String message, long retryAfterMillis) {
        super(message);
        this.retryAfterMillis = retryAfterMillis;
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }
}

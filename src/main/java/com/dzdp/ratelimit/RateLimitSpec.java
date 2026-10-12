package com.dzdp.ratelimit;

/**
 * 令牌桶规格：由 {@link RateLimitRule} 转换而来，与注解解耦，便于单测和替换实现。
 */
public final class RateLimitSpec {

    /** 补充周期上限：1 小时，防止误配置出超大周期导致桶永远补不满 */
    private static final long MAX_REFILL_PERIOD_MILLIS = 3_600_000L;

    /** 桶 key 的 TTL 上限：1 天，避免误配置导致 key 长期不回收 */
    private static final long MAX_TTL_MILLIS = 86_400_000L;

    private final int capacity;
    private final int refillTokens;
    private final long refillPeriodMillis;
    private final int requested;
    private final boolean failOpen;

    public RateLimitSpec(int capacity, int refillTokens, long refillPeriodMillis, int requested, boolean failOpen) {
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriodMillis = refillPeriodMillis;
        this.requested = requested;
        this.failOpen = failOpen;
    }

    public static RateLimitSpec from(RateLimitRule rule) {
        return new RateLimitSpec(rule.capacity(), rule.refillTokens(), rule.refillPeriodMillis(),
                rule.requested(), rule.failOpen());
    }

    /**
     * 校验规则是否可用。非法配置不放到 Lua 里执行，避免出现“永远补不满”的桶。
     */
    public boolean isValid() {
        return capacity >= 1
                && requested >= 1 && requested <= capacity
                && refillTokens >= 1 && refillTokens <= capacity
                && refillPeriodMillis >= 1L && refillPeriodMillis <= MAX_REFILL_PERIOD_MILLIS;
    }

    /**
     * 桶 key 的 TTL：补满整桶所需时间的两倍，保证桶能被自动回收。
     * 例：capacity=1、1 令牌/60s 得到 120s；capacity=30、20 令牌/s 得到 3s。
     */
    public long ttlMillis() {
        long fullRefillMillis = refillPeriodMillis * capacity / refillTokens;
        return Math.min(Math.max(fullRefillMillis * 2L, 1000L), MAX_TTL_MILLIS);
    }

    public int getCapacity() {
        return capacity;
    }

    public int getRefillTokens() {
        return refillTokens;
    }

    public long getRefillPeriodMillis() {
        return refillPeriodMillis;
    }

    public int getRequested() {
        return requested;
    }

    public boolean isFailOpen() {
        return failOpen;
    }
}

package com.dzdp.ratelimit;

/**
 * 限流器策略接口：只保留一个方法，便于替换实现（Redis 分布式 / 本地兜底）与单测。
 * 当前实现的算法统一为令牌桶。
 */
public interface RateLimiter {

    /**
     * 尝试获取令牌。
     *
     * @param key       桶 key，由 {@link RateLimitKeyResolver} 按限流维度生成
     * @param spec      令牌桶规格
     * @param nowMillis 当前时间戳（毫秒），由调用方传入，保证一次请求内多条规则使用同一时间基准
     * @return 判定结果；实现内部负责处理 Redis 异常与降级策略，不向外抛异常
     */
    RateLimitResult tryAcquire(String key, RateLimitSpec spec, long nowMillis);
}

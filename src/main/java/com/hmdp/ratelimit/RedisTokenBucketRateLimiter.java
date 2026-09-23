package com.hmdp.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis + Lua 的分布式令牌桶实现。
 *
 * <p>“查桶、补令牌、扣令牌、写回”由 rate_limit.lua 一次原子执行，因此多实例部署时桶是全局唯一的，
 * 不存在本地令牌桶“每个实例各放行一份”的问题。</p>
 *
 * <p>降级策略：Redis 异常或返回结果异常时不向外抛异常，而是按规则声明的 {@code failOpen} 决定
 * 放行（可用性优先）或拒绝（安全与成本优先），同时打 WARN 日志告警。</p>
 */
@Slf4j
@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    /** Redis 故障且规则要求 fail-closed 时，建议客户端稍后重试的时间（毫秒） */
    private static final long DEGRADE_RETRY_AFTER_MILLIS = 1000L;

    private static final int RESULT_SIZE = 3;

    private static final DefaultRedisScript<List> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public RedisTokenBucketRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public RateLimitResult tryAcquire(String key, RateLimitSpec spec, long nowMillis) {
        if (!spec.isValid()) {
            // 配置错误属于研发期问题：打错误日志并放行，避免把线上流量全部拦死
            log.error("[RATE-LIMIT] 令牌桶参数非法，本次放行 key={} capacity={} refillTokens={} "
                            + "refillPeriodMillis={} requested={}",
                    key, spec.getCapacity(), spec.getRefillTokens(), spec.getRefillPeriodMillis(), spec.getRequested());
            return RateLimitResult.allowed(spec.getCapacity());
        }

        try {
            List<?> result = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(spec.getCapacity()),
                    String.valueOf(spec.getRefillTokens()),
                    String.valueOf(spec.getRefillPeriodMillis()),
                    String.valueOf(nowMillis),
                    String.valueOf(spec.getRequested()),
                    String.valueOf(spec.ttlMillis()));

            if (result == null || result.size() < RESULT_SIZE) {
                log.warn("[RATE-LIMIT] 限流脚本返回结果异常，按 failOpen={} 降级 key={} result={}",
                        spec.isFailOpen(), key, result);
                return degrade(spec);
            }

            Long allowed = parseLong(result.get(0));
            Long remaining = parseLong(result.get(1));
            Long retryAfterMillis = parseLong(result.get(2));
            if (allowed == null || remaining == null || retryAfterMillis == null) {
                // 元素不可解析时不能当成"拒绝"，否则会影响全部放行判定；按降级策略处理并告警
                log.warn("[RATE-LIMIT] 限流脚本返回结果无法解析，按 failOpen={} 降级 key={} result={}",
                        spec.isFailOpen(), key, result);
                return degrade(spec);
            }
            return allowed == 1L
                    ? RateLimitResult.allowed(remaining)
                    : RateLimitResult.denied(remaining, retryAfterMillis);
        } catch (Exception e) {
            log.warn("[RATE-LIMIT] Redis 限流执行失败，按 failOpen={} 降级 key={} cause={}",
                    spec.isFailOpen(), key, e.toString());
            return degrade(spec);
        }
    }

    /**
     * 降级：failOpen=true 放行（保住可用性），failOpen=false 拒绝（保住安全与成本）。
     */
    private RateLimitResult degrade(RateLimitSpec spec) {
        return spec.isFailOpen()
                ? RateLimitResult.allowed(spec.getCapacity())
                : RateLimitResult.denied(0L, DEGRADE_RETRY_AFTER_MILLIS);
    }

    /**
     * Lua 返回的数字在不同序列化配置下可能是 Number 或 String，这里统一兼容；
     * 无法解析时返回 null，由调用方按降级策略处理。
     */
    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

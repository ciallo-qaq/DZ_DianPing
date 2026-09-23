package com.hmdp.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流声明式入口（令牌桶算法）。
 *
 * <p>用法：在 Controller 方法上声明一条或多条规则，规则之间是“与”关系，任意一条被限流即拒绝：</p>
 * <pre>
 * &#64;RateLimit({
 *     &#64;RateLimitRule(key = "user:code:phone", dimension = RateLimitDimension.PARAM,
 *             paramName = "phone", capacity = 1, refillTokens = 1, refillPeriodMillis = 60_000L),
 *     &#64;RateLimitRule(key = "user:code:ip", dimension = RateLimitDimension.IP,
 *             capacity = 10, refillTokens = 10, refillPeriodMillis = 60_000L)
 * })
 * </pre>
 *
 * <p>令牌计算与降级策略见 {@link RateLimiter}，切面只负责“解析标识 + 判定 + 抛异常”。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 该接口需要同时满足的多条令牌桶规则 */
    RateLimitRule[] value();
}

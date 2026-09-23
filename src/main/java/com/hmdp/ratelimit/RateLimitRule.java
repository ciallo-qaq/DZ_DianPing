package com.hmdp.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 单条令牌桶规则。一个接口可以同时声明多条规则（例如“每手机号 1 次/分钟” + “每 IP 10 次/分钟”）。
 *
 * <p>参数取舍的关键在 {@link #capacity()}：capacity 越大允许的突发越强；
 * capacity=1 时令牌桶退化为“每次操作后必须等一个补充周期”，近似严格均匀限流，适合防刷场景。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimitRule {

    /**
     * 规则名：用于拼桶 key 与日志，不要写入手机号、IP 等隐私数据。
     * 建议格式：业务:接口，例如 user:code:phone。
     */
    String key();

    /** 限流维度，默认按 IP */
    RateLimitDimension dimension() default RateLimitDimension.IP;

    /**
     * 需要取方法参数时使用，按顺序尝试三种写法：
     * 1) @RequestParam / @PathVariable 上声明的名字；
     * 2) 形参名（需编译期保留参数名）；
     * 3) 请求体对象的属性名，如 LoginFormDTO#phone。
     */
    String paramName() default "";

    /** 需要取方法参数时可直接指定下标，>=0 时优先于 {@link #paramName()} */
    int paramIndex() default -1;

    /** 桶容量：允许的最大突发令牌数，也是防刷强度的总开关 */
    int capacity() default 10;

    /** 一个补充周期内补充的令牌数 */
    int refillTokens() default 10;

    /** 补充周期（毫秒） */
    long refillPeriodMillis() default 1000L;

    /** 单次请求消耗的令牌数 */
    int requested() default 1;

    /** Redis 故障时的降级策略：true=放行（可用性优先），false=拒绝（安全与成本优先） */
    boolean failOpen() default true;

    /** 被限流时的提示语，留空使用默认提示 */
    String message() default "";
}

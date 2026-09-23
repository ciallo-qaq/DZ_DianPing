package com.hmdp.ratelimit;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 限流切面：解析规则 -> 生成桶 key -> 向令牌桶取令牌 -> 放行或抛 {@link RateLimitException}。
 *
 * <p>顺序为 {@code @Order(-1)}，比 ApiTraceAspect 的 0 更靠外：被限流的请求在进入业务与事务之前
 * 就被拦掉，不会占用数据库连接。</p>
 *
 * <p>切面只做适配，令牌计算与降级都在 {@link RateLimiter} 中完成。</p>
 */
@Slf4j
@Aspect
@Component
@Order(-1)
public class RateLimitAspect {

    private static final String DEFAULT_MESSAGE = "请求过于频繁，请稍后再试";

    private final RateLimiter rateLimiter;
    private final RateLimitKeyResolver keyResolver;
    private final RateLimitProperties properties;

    public RateLimitAspect(RateLimiter rateLimiter, RateLimitKeyResolver keyResolver, RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.properties = properties;
    }

    @Around("@annotation(com.hmdp.ratelimit.RateLimit)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        if (!properties.isEnabled()) {
            return point.proceed();
        }

        RateLimit annotation = findAnnotation(point);
        if (annotation == null || annotation.value().length == 0) {
            return point.proceed();
        }

        String clientIp = keyResolver.clientIp();
        if (properties.isWhitelisted(clientIp)) {
            log.debug("[RATE-LIMIT] 命中 IP 白名单，跳过限流 ip={}", clientIp);
            return point.proceed();
        }

        // 未登录接口 UserHolder 为空，此时 USER 维度会自动退化为 IP 维度
        UserDTO user = UserHolder.getUser();
        // 一次请求内多条规则共用同一时间基准，避免同一请求被判断成跨了补充周期
        long nowMillis = System.currentTimeMillis();

        for (RateLimitRule rule : annotation.value()) {
            String key = keyResolver.resolve(rule, point, user);
            if (key == null) {
                log.warn("[RATE-LIMIT] 无法解析限流标识，本次放行 rule={} dimension={} method={}",
                        rule.key(), rule.dimension(), point.getSignature().toShortString());
                continue;
            }

            RateLimitResult result = rateLimiter.tryAcquire(key, RateLimitSpec.from(rule), nowMillis);
            if (!result.isAllowed()) {
                long retryAfterMillis = Math.max(result.getRetryAfterMillis(), 1L);
                // 只记录规则名与维度，不记录含 IP/手机号的完整桶 key
                log.warn("[RATE-LIMIT] 命中限流 rule={} dimension={} remaining={} retryAfterMs={}",
                        rule.key(), rule.dimension(), result.getRemaining(), retryAfterMillis);
                throw new RateLimitException(message(rule), retryAfterMillis);
            }
        }

        return point.proceed();
    }

    private String message(RateLimitRule rule) {
        String message = rule.message();
        return message == null || message.trim().isEmpty() ? DEFAULT_MESSAGE : message;
    }

    private RateLimit findAnnotation(ProceedingJoinPoint point) {
        Method method = ((MethodSignature) point.getSignature()).getMethod();
        RateLimit annotation = AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), RateLimit.class);
        }
        return annotation;
    }
}

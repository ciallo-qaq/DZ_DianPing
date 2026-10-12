package com.dzdp.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Redis 令牌桶限流器的单元验证：用桩 StringRedisTemplate 固定 Lua 返回值，
 * 覆盖放行、限流、结果解析、参数透传与 Redis 故障降级策略，不依赖真实 Redis。
 *
 * <p>运行方式：mvn test -Dtest=RedisTokenBucketRateLimiterTest</p>
 */
@DisplayName("Redis 令牌桶限流器")
class RedisTokenBucketRateLimiterTest {

    private static final String KEY = "ratelimit:test:ip|10.0.0.9";
    private static final long NOW = 1_700_000_000_000L;

    private StringRedisTemplate template;
    private RedisTokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        limiter = new RedisTokenBucketRateLimiter(template);
    }

    @Test
    @DisplayName("放行：解析 allowed / remaining / retryAfter")
    void shouldParseAllowedResult() {
        stubScriptResult(Arrays.asList(1L, 4L, 0L));

        RateLimitResult result = limiter.tryAcquire(KEY, spec(5, 5, 60_000L, true), NOW);

        assertTrue(result.isAllowed());
        assertEquals(4L, result.getRemaining());
        assertEquals(0L, result.getRetryAfterMillis());
    }

    @Test
    @DisplayName("限流：Lua 返回字符串数字时同样能正确解析重试时间")
    void shouldParseDeniedResultFromStringValues() {
        stubScriptResult(Arrays.asList("0", "1", "1500"));

        RateLimitResult result = limiter.tryAcquire(KEY, spec(2, 1, 1_000L, true), NOW);

        assertFalse(result.isAllowed());
        assertEquals(1L, result.getRemaining());
        assertEquals(1500L, result.getRetryAfterMillis());
    }

    @Test
    @DisplayName("按约定向 Lua 传参：容量、补充速率、周期、时间戳、消耗量、TTL")
    void shouldPassSpecToScript() {
        stubScriptResult(Arrays.asList(1L, 0L, 0L));

        limiter.tryAcquire(KEY, spec(5, 2, 1_000L, true), 123_456_789L);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> argvCaptor = ArgumentCaptor.forClass(Object.class);
        verify(template).execute(any(RedisScript.class), keysCaptor.capture(),
                argvCaptor.capture(), argvCaptor.capture(), argvCaptor.capture(),
                argvCaptor.capture(), argvCaptor.capture(), argvCaptor.capture());

        assertEquals(Collections.singletonList(KEY), keysCaptor.getValue());
        // TTL = 补满整桶耗时(1000ms * 5 / 2 = 2500ms) * 2 = 5000ms
        assertEquals(Arrays.asList("5", "2", "1000", "123456789", "1", "5000"), asStrings(argvCaptor.getAllValues()));
    }

    @Test
    @DisplayName("Redis 故障 + failOpen=true：放行，保证可用性")
    void shouldFailOpenWhenRedisDown() {
        stubRedisFailure();

        RateLimitResult result = limiter.tryAcquire(KEY, spec(5, 5, 60_000L, true), NOW);

        assertTrue(result.isAllowed());
    }

    @Test
    @DisplayName("Redis 故障 + failOpen=false：拒绝，并给出建议重试时间")
    void shouldFailClosedWhenRedisDown() {
        stubRedisFailure();

        RateLimitResult result = limiter.tryAcquire(KEY, spec(5, 5, 60_000L, false), NOW);

        assertFalse(result.isAllowed());
        assertEquals(1000L, result.getRetryAfterMillis());
    }

    @Test
    @DisplayName("脚本返回结果异常时按 failOpen 降级")
    void shouldDegradeWhenScriptResultMalformed() {
        stubScriptResult(null);
        assertTrue(limiter.tryAcquire(KEY, spec(5, 5, 60_000L, true), NOW).isAllowed());

        stubScriptResult(Collections.singletonList(1L));
        assertFalse(limiter.tryAcquire(KEY, spec(5, 5, 60_000L, false), NOW).isAllowed());

        // 元素不可解析（如序列化器变更返回 byte[]）时不能当成"一律拒绝"
        stubScriptResult(Arrays.asList("x", "1", "2"));
        assertTrue(limiter.tryAcquire(KEY, spec(5, 5, 60_000L, true), NOW).isAllowed());
    }

    @Test
    @DisplayName("规格非法时告警放行，且不访问 Redis")
    void shouldRejectInvalidSpecWithoutTouchingRedis() {
        assertTrue(limiter.tryAcquire(KEY, spec(0, 1, 1_000L, false), NOW).isAllowed());

        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("规格校验与桶 TTL 计算")
    void shouldValidateSpecAndCalculateTtl() {
        assertTrue(spec(1, 1, 60_000L, true).isValid());
        assertTrue(spec(30, 20, 1_000L, true).isValid());

        assertFalse(spec(0, 1, 1_000L, true).isValid(), "容量必须 >= 1");
        assertFalse(spec(1, 2, 1_000L, true).isValid(), "单周期补充量不能超过桶容量");
        assertFalse(spec(1, 1, 0L, true).isValid(), "补充周期必须 >= 1ms");
        assertFalse(new RateLimitSpec(2, 2, 1_000L, 3, true).isValid(), "单次消耗不能超过桶容量");

        assertEquals(120_000L, spec(1, 1, 60_000L, true).ttlMillis());
        assertEquals(3_000L, spec(30, 20, 1_000L, true).ttlMillis());
    }

    /* ------------------------------------------------------------------ 测试脚手架 */

    private RateLimitSpec spec(int capacity, int refillTokens, long refillPeriodMillis, boolean failOpen) {
        return new RateLimitSpec(capacity, refillTokens, refillPeriodMillis, 1, failOpen);
    }

    private void stubScriptResult(List<?> result) {
        doReturn(result).when(template).execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private void stubRedisFailure() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(template).execute(any(RedisScript.class), anyList(),
                        anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private List<String> asStrings(List<Object> values) {
        List<String> strings = new ArrayList<>(values.size());
        for (Object value : values) {
            strings.add(String.valueOf(value));
        }
        return strings;
    }
}

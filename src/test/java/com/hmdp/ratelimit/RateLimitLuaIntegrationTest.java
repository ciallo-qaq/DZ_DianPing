package com.hmdp.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 令牌桶 Lua 脚本的端到端验证：直连真实 Redis，验证补充速率、capacity=1 的防刷语义、
 * 时钟回拨保护与桶 key 自动回收。
 *
 * <p>本机没有 Redis 时整类自动跳过（不会让构建失败），连接参数可用系统属性覆盖：</p>
 * <pre>
 * mvn test -Dtest=RateLimitLuaIntegrationTest \
 *     -Dratelimit.redis.host=127.0.0.1 -Dratelimit.redis.port=6380 -Dratelimit.redis.password=123456
 * </pre>
 */
@DisplayName("令牌桶 Lua 脚本（需要真实 Redis）")
class RateLimitLuaIntegrationTest {

    private static final String HOST = System.getProperty("ratelimit.redis.host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("ratelimit.redis.port", "6380"));
    private static final String PASSWORD = System.getProperty("ratelimit.redis.password", "123456");

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void setUp() {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(HOST, PORT);
        serverConfig.setPassword(PASSWORD);

        // 命令超时设短一些：Redis 不可用时快速跳过，而不是卡住整个测试
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(1500))
                .shutdownTimeout(Duration.ZERO)
                .build();

        factory = new LettuceConnectionFactory(serverConfig, clientConfig);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        boolean reachable = false;
        String cause = "";
        try {
            template.getConnectionFactory().getConnection().ping();
            reachable = true;
        } catch (Exception e) {
            cause = e.toString();
        }
        assumeTrue(reachable, "Redis(" + HOST + ":" + PORT + ") 不可用，跳过 Lua 集成验证: " + cause);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("容量决定突发上限，补充速率决定平均速率")
    void shouldLimitByCapacityAndRefillRate() {
        String key = key();
        RateLimitSpec spec = new RateLimitSpec(2, 1, 1_000L, 1, true);
        long now = System.currentTimeMillis();

        assertTrue(acquire(key, spec, now).isAllowed(), "桶内有 2 个令牌，第 1 次应放行");
        assertTrue(acquire(key, spec, now).isAllowed(), "第 2 次应放行");

        RateLimitResult denied = acquire(key, spec, now);
        assertFalse(denied.isAllowed(), "第 3 次应被限流");
        assertTrue(denied.getRetryAfterMillis() > 0 && denied.getRetryAfterMillis() <= 1_000L,
                "重试时间应在 1s 内: " + denied.getRetryAfterMillis());

        assertTrue(acquire(key, spec, now + 1_000L).isAllowed(), "补 1 秒后应再放行 1 次");
    }

    @Test
    @DisplayName("capacity=1 的防刷语义：每次使用后必须等一个补充周期，攒不出突发")
    void shouldBehaveAsCooldownWhenCapacityIsOne() {
        String key = key();
        RateLimitSpec spec = new RateLimitSpec(1, 1, 2_000L, 1, true);
        long now = System.currentTimeMillis();

        assertTrue(acquire(key, spec, now).isAllowed());

        RateLimitResult denied = acquire(key, spec, now);
        assertFalse(denied.isAllowed());
        assertEquals(2_000L, denied.getRetryAfterMillis(), "应提示等一个完整补充周期");
    }

    @Test
    @DisplayName("时钟回拨不会补出额外令牌")
    void shouldNotRefillOnClockSkew() {
        String key = key();
        RateLimitSpec spec = new RateLimitSpec(1, 1, 1_000L, 1, true);
        long now = System.currentTimeMillis();

        assertTrue(acquire(key, spec, now + 60_000L).isAllowed());
        assertFalse(acquire(key, spec, now).isAllowed(), "时间倒退时应按当前时间处理，不得补令牌");
    }

    @Test
    @DisplayName("桶 key 会在补满整桶的两倍时间后自动回收")
    void shouldExpireBucketKey() {
        String key = key();
        RateLimitSpec spec = new RateLimitSpec(1, 1, 1_000L, 1, true);

        acquire(key, spec, System.currentTimeMillis());

        Long ttlSeconds = template.getExpire(key);
        assertNotNull(ttlSeconds);
        assertTrue(ttlSeconds > 0 && ttlSeconds <= 2, "TTL 应为 2s: " + ttlSeconds);
    }

    private static String key() {
        return "ratelimit:it:" + UUID.randomUUID();
    }

    private RateLimitResult acquire(String key, RateLimitSpec spec, long nowMillis) {
        return new RedisTokenBucketRateLimiter(template).tryAcquire(key, spec, nowMillis);
    }
}

package com.dzdp.ratelimit;

import com.dzdp.dto.LoginFormDTO;
import com.dzdp.dto.UserDTO;
import com.dzdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流切面与桶 key 解析的单元验证：不依赖 Redis/MySQL，
 * 通过桩限流器断言真实传入的桶 key，通过异常断言限流提示与重试时间。
 *
 * <p>运行方式：mvn test -Dtest=RateLimitAspectTest</p>
 */
@DisplayName("令牌桶限流切面")
class RateLimitAspectTest {

    private RateLimitProperties properties;
    private RecordingRateLimiter limiter;
    private DemoController proxy;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        limiter = new RecordingRateLimiter();
        proxy = proxy(new RateLimitAspect(limiter, new RateLimitKeyResolver(properties), properties));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        UserHolder.removeUser();
    }

    @Test
    @DisplayName("PARAM 与 IP 两条规则各自生成独立的桶 key")
    void shouldBuildIndependentKeysForEachRule() {
        request("10.0.0.9", null, null);

        assertEquals("code:13800138000", proxy.code("13800138000"));

        assertEquals(2, limiter.keys.size());
        assertEquals("ratelimit:demo:phone:param|13800138000", limiter.keys.get(0));
        assertEquals("ratelimit:demo:ip:ip|10.0.0.9", limiter.keys.get(1));
    }

    @Test
    @DisplayName("请求体对象的属性也可以作为限流标识（如 LoginFormDTO#phone）")
    void shouldReadIdentityFromRequestBody() {
        request("10.0.0.9", null, null);

        LoginFormDTO form = LoginFormDTO.builder().phone("13900139000").code("123456").build();
        assertEquals("login:13900139000", proxy.login(form));

        assertEquals(Collections.singletonList("ratelimit:demo:body:param|13900139000"), limiter.keys);
    }

    @Test
    @DisplayName("命中限流：抛出异常并带上规则提示语与重试时间，且后续规则不再执行")
    void shouldThrowWithRuleMessage() {
        request("10.0.0.9", null, null);
        limiter.results.add(RateLimitResult.denied(0L, 1500L));

        RateLimitException exception = assertThrows(RateLimitException.class, () -> proxy.code("13800138000"));

        assertEquals("手机号太频繁", exception.getMessage());
        assertEquals(1500L, exception.getRetryAfterMillis());
        assertEquals(1, limiter.keys.size());
    }

    @Test
    @DisplayName("规则未配置提示语时使用默认提示")
    void shouldUseDefaultMessage() {
        request("10.0.0.9", null, null);
        limiter.results.add(RateLimitResult.allowed(0L));
        limiter.results.add(RateLimitResult.denied(0L, 2500L));

        RateLimitException exception = assertThrows(RateLimitException.class, () -> proxy.code("13800138000"));

        assertEquals("请求过于频繁，请稍后再试", exception.getMessage());
        assertEquals(2500L, exception.getRetryAfterMillis());
    }

    @Test
    @DisplayName("USER 维度优先使用登录用户 id")
    void shouldUseUserIdWhenLoggedIn() {
        request("10.0.0.9", null, null);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);

        assertEquals("me", proxy.me());

        assertEquals(Collections.singletonList("ratelimit:demo:user:user|7"), limiter.keys);
    }

    @Test
    @DisplayName("无法解析标识（无请求上下文、无登录用户）时直接放行")
    void shouldProceedWhenIdentityUnresolvable() {
        RequestContextHolder.resetRequestAttributes();

        assertEquals("me", proxy.me());

        assertTrue(limiter.keys.isEmpty());
    }

    @Test
    @DisplayName("IP 取值优先 X-Real-IP，并对伪造的 X-Forwarded-For 做字符过滤")
    void shouldResolveIpSafely() {
        request("10.0.0.9", "1.1.1.1", "evil;{x}");
        proxy.code("13800138000");
        assertEquals("ratelimit:demo:ip:ip|1.1.1.1", limiter.keys.get(1));

        limiter.keys.clear();
        request("10.0.0.9", null, "9.9.9.9, evil;{x}");
        proxy.code("13800138000");
        assertEquals("ratelimit:demo:ip:ip|evil__x_", limiter.keys.get(1));
    }

    @Test
    @DisplayName("IP 命中白名单时完全跳过限流")
    void shouldSkipWhitelistedIp() {
        properties.setWhitelistIps(Collections.singletonList("10.0.0.9"));
        request("10.0.0.9", null, null);

        assertEquals("code:13800138000", proxy.code("13800138000"));

        assertTrue(limiter.keys.isEmpty());
    }

    @Test
    @DisplayName("总开关关闭时完全跳过限流")
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);
        request("10.0.0.9", null, null);

        assertEquals("code:13800138000", proxy.code("13800138000"));

        assertTrue(limiter.keys.isEmpty());
    }

    /* ------------------------------------------------------------------ 测试脚手架 */

    private DemoController proxy(RateLimitAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new DemoController());
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private void request(String remoteAddr, String realIp, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demo");
        request.setRemoteAddr(remoteAddr);
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 记录桶 key 与规则，并按队列返回预设判定结果，默认放行 */
    private static class RecordingRateLimiter implements RateLimiter {

        private final List<String> keys = new ArrayList<>();
        private final List<RateLimitSpec> specs = new ArrayList<>();
        private final Deque<RateLimitResult> results = new ArrayDeque<>();

        @Override
        public RateLimitResult tryAcquire(String key, RateLimitSpec spec, long nowMillis) {
            keys.add(key);
            specs.add(spec);
            return results.isEmpty() ? RateLimitResult.allowed(1L) : results.poll();
        }
    }

    /** 被切面代理的示例接口 */
    static class DemoController {

        @RateLimit({
                @RateLimitRule(key = "demo:phone", dimension = RateLimitDimension.PARAM, paramName = "phone",
                        capacity = 1, refillTokens = 1, refillPeriodMillis = 60_000L, message = "手机号太频繁"),
                @RateLimitRule(key = "demo:ip", dimension = RateLimitDimension.IP,
                        capacity = 10, refillTokens = 10, refillPeriodMillis = 60_000L)
        })
        public String code(@RequestParam("phone") String phone) {
            return "code:" + phone;
        }

        @RateLimit(@RateLimitRule(key = "demo:body", dimension = RateLimitDimension.PARAM, paramName = "phone",
                capacity = 5, refillTokens = 5, refillPeriodMillis = 60_000L))
        public String login(@RequestBody LoginFormDTO form) {
            return "login:" + form.getPhone();
        }

        @RateLimit(@RateLimitRule(key = "demo:user", dimension = RateLimitDimension.USER,
                capacity = 3, refillTokens = 3, refillPeriodMillis = 1_000L))
        public String me() {
            return "me";
        }
    }
}

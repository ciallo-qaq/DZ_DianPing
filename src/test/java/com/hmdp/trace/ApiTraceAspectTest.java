package com.hmdp.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hmdp.dto.Result;
import com.hmdp.service.TraceDemoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiTraceAspect 的单元验证：不依赖 MySQL/Redis/RabbitMQ，直接对切面生成的代理发起调用，
 * 通过 logback ListAppender 断言日志内容。
 *
 * <p>运行方式：mvn test -Dtest=ApiTraceAspectTest</p>
 */
@DisplayName("接口性能采集切面")
class ApiTraceAspectTest {

    private TraceProperties properties;
    private Logger aspectLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        properties = new TraceProperties();
        properties.setEnabled(true);
        properties.setSlowMs(1000L);
        properties.setServiceSlowMs(0L);

        aspectLogger = (Logger) LoggerFactory.getLogger(ApiTraceAspect.class);
        appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        aspectLogger.detachAppender(appender);
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("输出 URL、请求类型、请求参数、返回码、耗时与 Service 明细")
    void shouldTraceApiAndService() {
        try (RequestScope ignored = mockRequest("POST", "/shop/{id}", "id=12")) {
            DemoController controller = controller();
            Result result = controller.query(12L);
            assertTrue(result.getSuccess());
        }

        String logs = logs();
        assertTrue(logs.contains("uri=/shop/{id}"), logs);
        assertTrue(logs.contains("method=POST"), logs);
        assertTrue(logs.contains("\"id\":\"12\""), logs);
        assertTrue(logs.contains("biz=OK"), logs);
        assertTrue(logs.contains("costMs="), logs);
        assertTrue(logs.contains("ctrlMs="), logs);
        assertTrue(logs.contains("[SVC] TraceDemoService#doWork"), logs);
    }

    @Test
    @DisplayName("Service 慢调用会标记 SLOW 并给出耗时")
    void shouldMarkSlowService() throws Exception {
        // 阈值设为 0，让汇总行同时落入慢接口分支，验证 WARN 级别
        properties.setSlowMs(0L);
        try (RequestScope ignored = mockRequest("GET", "/demo/slow", null)) {
            controller().slow();
        }

        String logs = logs();
        assertTrue(logs.contains("[SVC] TraceDemoService#slowWork"), logs);
        assertTrue(logs.contains("[SLOW]"), logs);
        assertEquals(Level.WARN, appender.list.get(appender.list.size() - 1).getLevel(), logs);
    }

    @Test
    @DisplayName("敏感字段脱敏：query 参数与请求体 password 都不落原文")
    void shouldMaskSensitiveArguments() {
        try (RequestScope ignored = mockRequest("POST", "/user/login", null)) {
            DemoController controller = controller();
            controller.phone("13800138000");
            controller.login(body("password", "123456"));
        }

        String logs = logs();
        assertTrue(logs.contains("\"phone\":\"***\""), logs);
        assertTrue(logs.contains("\"password\":\"***\""), logs);
        assertFalse(logs.contains("13800138000"), logs);
        assertFalse(logs.contains("123456"), logs);
    }

    @Test
    @DisplayName("异常监控：标记 EXCEPTION 且异常继续向上抛出")
    void shouldRecordExceptionAndRethrow() {
        try (RequestScope ignored = mockRequest("GET", "/demo/boom", null)) {
            DemoController controller = controller();
            IllegalStateException error = assertThrows(IllegalStateException.class, controller::boom);
            assertEquals("demo-boom", error.getMessage());
        }

        String logs = logs();
        assertTrue(logs.contains("biz=EXCEPTION"), logs);
        assertTrue(logs.contains("demo-boom"), logs);
    }

    @Test
    @DisplayName("开关关闭时完全不采集")
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);
        try (RequestScope ignored = mockRequest("GET", "/shop/{id}", null)) {
            controller().query(1L);
        }
        assertTrue(logs().isEmpty(), logs());
    }

    /* ------------------------------------------------------------------ 测试脚手架 */

    private DemoController controller() {
        AspectJProxyFactory serviceFactory = new AspectJProxyFactory(new TraceDemoService());
        serviceFactory.addAspect(new ApiTraceAspect(properties));
        TraceDemoService service = serviceFactory.getProxy();

        AspectJProxyFactory controllerFactory = new AspectJProxyFactory(new DemoController(service));
        controllerFactory.addAspect(new ApiTraceAspect(properties));
        return controllerFactory.getProxy();
    }

    private RequestScope mockRequest(String method, String pattern, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, pattern);
        if (query != null) {
            String[] pair = query.split("=");
            request.addParameter(pair[0], pair[1]);
        }
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        return new RequestScope();
    }

    private Map<String, String> body(String key, String value) {
        Map<String, String> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private String logs() {
        return appender.list.stream()
                .map(event -> event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                        ? ""
                        : " " + event.getThrowableProxy().getClassName() + ": " + event.getThrowableProxy().getMessage()))
                .collect(Collectors.joining("\n"));
    }

    /** 让 RequestContextHolder 的清理写在 try-with-resources 里，避免测试之间互相污染 */
    private static final class RequestScope implements AutoCloseable {
        @Override
        public void close() {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** 被 @ApiTrace 标记的示例 Controller，用来说明注解的实际效果 */
    public static class DemoController {

        private final TraceDemoService service;

        public DemoController(TraceDemoService service) {
            this.service = service;
        }

        @ApiTrace
        public Result query(@RequestParam("id") Long id) {
            service.doWork();
            return Result.ok(id);
        }

        @ApiTrace
        public Result slow() throws InterruptedException {
            service.slowWork();
            return Result.ok();
        }

        @ApiTrace
        public Result phone(@RequestParam("phone") String phone) {
            return Result.ok(phone);
        }

        @ApiTrace
        public Result login(@RequestBody Map<String, String> body) {
            return Result.ok(body);
        }

        @ApiTrace
        public Result boom() {
            service.doWork();
            throw new IllegalStateException("demo-boom");
        }
    }
}

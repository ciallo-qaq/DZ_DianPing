package com.dzdp.trace;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.dzdp.dto.Result;
import com.dzdp.dto.UserDTO;
import com.dzdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.util.ClassUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 开发期接口/Service 性能采集切面，是整个方案唯一的核心类。
 *
 * <p>两个切点：</p>
 * <ol>
 *   <li>入口切点：只拦被 {@link ApiTrace} 标记的 Controller 类或方法，
 *       负责 URL、请求类型、请求参数、返回码、总耗时、Controller 耗时、异常，并负责输出日志与清理上下文。</li>
 *   <li>Service 切点：按包路径拦截 com.dzdp.service 下的所有实现，
 *       通过 {@link TraceContextHolder} 复用当前请求上下文累加耗时，因此 Service 不需要再加注解。</li>
 * </ol>
 *
 * <p>设计约束：切面内所有采集动作都必须可失败，采集异常只允许降级成一条 WARN 日志，
 * 绝不能影响业务方法的正常返回或异常抛出。</p>
 */
@Slf4j
@Aspect
@Component
@Order(0)
public class ApiTraceAspect {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final TraceProperties properties;

    public ApiTraceAspect(TraceProperties properties) {
        this.properties = properties;
    }

    /* ------------------------------------------------------------------ 入口切面 */

    /**
     * 入口切面：@within 覆盖类上的注解，@annotation 覆盖方法上的注解。
     * 顺序设为最高优先级，保证它比事务切面更外层，耗时统计才包含事务提交。
     */
    @Around("@within(com.dzdp.trace.ApiTrace) || @annotation(com.dzdp.trace.ApiTrace)")
    public Object traceApi(ProceedingJoinPoint point) throws Throwable {
        // 开关关闭时零开销放行，行为与改造前完全一致
        if (!properties.isEnabled()) {
            return point.proceed();
        }

        ApiTrace annotation = findAnnotation(point);
        TraceContext context = new TraceContext();
        context.setTraceId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        TraceContextHolder.set(context);

        long enterNanos = System.nanoTime();
        long methodStartNanos = System.nanoTime();
        try {
            fillRequest(context, point, annotation);
            Object result = point.proceed();
            context.setControllerCostMs(costMs(methodStartNanos));
            fillBizResult(context, result);
            return result;
        } catch (Throwable throwable) {
            // 只记录，不吞掉异常，WebExceptionAdvice 的原有行为保持不变
            context.setControllerCostMs(costMs(methodStartNanos));
            context.setException(throwable);
            throw throwable;
        } finally {
            context.setTotalCostMs(costMs(enterNanos));
            fillHttpStatus(context);
            try {
                output(context, annotation);
            } catch (Exception logError) {
                // 采集本身失败不能影响业务
                log.warn("[API-TRACE] 采集日志输出失败: {}", logError.toString());
            }
            // 必须清理，防止 Tomcat 线程复用导致数据串请求
            TraceContextHolder.clear();
        }
    }

    /* ------------------------------------------------------------------ Service 切面 */

    /**
     * Service 切面：拦截 com.dzdp.service 包及其子包下的所有 public 方法。
     * 没有请求上下文（如 RabbitMQ 消费、线程池异步任务）时直接放行，不做采集。
     */
    @Around("execution(public * com.dzdp.service..*(..))")
    public Object traceService(ProceedingJoinPoint point) throws Throwable {
        TraceContext context = TraceContextHolder.getCurrent();
        if (!properties.isEnabled() || context == null) {
            return point.proceed();
        }
        long startNanos = System.nanoTime();
        try {
            return point.proceed();
        } finally {
            try {
                context.recordService(serviceName(point), costMs(startNanos));
            } catch (Exception ignore) {
                // 统计失败不影响业务
            }
        }
    }

    /* ------------------------------------------------------------------ 请求信息采集 */

    private void fillRequest(TraceContext context, ProceedingJoinPoint point, ApiTrace annotation) {
        context.setApiName(resolveApiName(point, annotation));
        context.setUserId(currentUserId());
        context.setParams(collectParams(point, annotation));

        ServletRequestAttributes attributes = currentRequest();
        if (attributes == null) {
            // 单元测试、非 Web 调用等场景没有 request，保留默认值即可
            return;
        }
        HttpServletRequest request = attributes.getRequest();
        // 用路径模板而不是真实 URI，否则 /shop/1、/shop/2 会被当成两个接口，统计失去意义
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        context.setUri(pattern == null ? request.getRequestURI() : pattern.toString());
        context.setHttpMethod(request.getMethod());
    }

    private void fillBizResult(TraceContext context, Object result) {
        if (result instanceof Result) {
            Result body = (Result) result;
            context.setBizSuccess(body.getSuccess());
            context.setBizMsg(body.getErrorMsg());
        }
    }

    private void fillHttpStatus(TraceContext context) {
        ServletRequestAttributes attributes = currentRequest();
        if (attributes != null && attributes.getResponse() != null) {
            context.setHttpStatus(attributes.getResponse().getStatus());
        }
    }

    /**
     * 采集请求参数：只处理方法入参，跳过容器类型，敏感字段脱敏，最后统一截断。
     * 因此不需要额外的"忽略注解"，忽略规则全部内置。
     */
    private String collectParams(ProceedingJoinPoint point, ApiTrace annotation) {
        if (annotation != null && !annotation.args()) {
            return "-";
        }
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        String[] parameterNames = signature.getParameterNames();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Object[] args = point.getArgs();
        if (args == null || args.length == 0) {
            return "-";
        }

        Map<String, Object> params = new LinkedHashMap<>();
        int limit = Math.max(1, properties.getMaxParams());
        for (int i = 0; i < args.length && params.size() < limit; i++) {
            if (skip(args[i])) {
                continue;
            }
            String name = parameterName(parameterNames, i, parameterAnnotations[i]);
            params.put(name, isSensitive(name) ? "***" : readableValue(args[i]));
        }
        if (params.isEmpty()) {
            return "-";
        }

        String text = JSONUtil.toJsonStr(params);
        // 请求体里的敏感字段（如 LoginFormDTO.password）在 JSON 里，需要再按字段名做一次脱敏
        return truncate(mask(text));
    }

    /** 容器类型参数没有调试价值，直接跳过 */
    private boolean skip(Object arg) {
        return arg == null
                || arg instanceof HttpServletRequest
                || arg instanceof HttpServletResponse
                || arg instanceof HttpSession
                || arg instanceof MultipartFile
                || arg instanceof Model
                || arg instanceof BindingResult;
    }

    /**
     * 参数名优先取编译期参数名，取不到时回退到 @RequestParam/@PathVariable 上声明的名字，
     * 再取不到才用 arg0/arg1，保证日志里看到的始终是业务字段名。
     */
    private String parameterName(String[] parameterNames, int index, Annotation[] annotations) {
        if (parameterNames != null && index < parameterNames.length && StrUtil.isNotBlank(parameterNames[index])) {
            return parameterNames[index];
        }
        for (Annotation annotation : annotations) {
            if (annotation instanceof RequestParam && StrUtil.isNotBlank(((RequestParam) annotation).value())) {
                return ((RequestParam) annotation).value();
            }
            if (annotation instanceof PathVariable && StrUtil.isNotBlank(((PathVariable) annotation).value())) {
                return ((PathVariable) annotation).value();
            }
            if (annotation instanceof RequestBody) {
                return "body";
            }
        }
        return "arg" + index;
    }

    /** 简单类型直接转字符串，复杂对象转成 JSON 便于阅读 */
    private Object readableValue(Object value) {
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum) {
            return String.valueOf(value);
        }
        try {
            return JSONUtil.parse(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** 字段名命中脱敏清单（大小写不敏感） */
    private boolean isSensitive(String name) {
        if (StrUtil.isBlank(name) || properties.getMaskKeys() == null) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String key : properties.getMaskKeys()) {
            if (StrUtil.isNotBlank(key) && lower.contains(key.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 对已序列化的文本按字段名做脱敏，覆盖 JSON 与 key=value 两种形态 */
    private String mask(String text) {
        if (StrUtil.isBlank(text) || properties.getMaskKeys() == null) {
            return text;
        }
        String masked = text;
        for (String key : properties.getMaskKeys()) {
            if (StrUtil.isBlank(key)) {
                continue;
            }
            String regex = "(?i)(\"?" + Pattern.quote(key) + "\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^,}\\s]+)";
            try {
                masked = Pattern.compile(regex).matcher(masked).replaceAll("$1\"***\"");
            } catch (Exception ignore) {
                // 单个字段脱敏失败不影响其他字段
            }
        }
        return masked;
    }

    private String truncate(String text) {
        int max = Math.max(32, properties.getMaxArgLength());
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...(len=" + text.length() + ")";
    }

    /* ------------------------------------------------------------------ 日志输出 */

    private void output(TraceContext context, ApiTrace annotation) {
        long slowMs = annotation != null && annotation.slowMs() > 0 ? annotation.slowMs() : properties.getSlowMs();
        boolean exception = context.getException() != null;
        boolean slow = context.getTotalCostMs() >= slowMs;

        StringBuilder line = new StringBuilder(256);
        line.append("[API-TRACE] traceId=").append(context.getTraceId())
                .append(" uri=").append(context.getUri())
                .append(" method=").append(context.getHttpMethod())
                .append(" params=").append(context.getParams())
                .append(" api=").append(context.getApiName())
                .append(" user=").append(context.getUserId() == null ? "-" : context.getUserId())
                .append(" status=").append(context.getHttpStatus())
                .append(" biz=").append(describeBiz(context))
                .append(" costMs=").append(context.getTotalCostMs())
                .append(" ctrlMs=").append(context.getControllerCostMs())
                .append(" svcMs=").append(context.getServiceTotalMs());
        if (slow) {
            line.append(" [SLOW>").append(slowMs).append("ms]");
        }
        line.append(serviceDetail(context));

        if (exception) {
            // 打异常时把堆栈一起带上，便于直接定位
            log.error(line.toString(), context.getException());
        } else if (slow) {
            log.warn(line.toString());
        } else {
            log.info(line.toString());
        }
    }

    private String describeBiz(TraceContext context) {
        if (context.getException() != null) {
            return "EXCEPTION";
        }
        if (context.getBizSuccess() == null) {
            return "-";
        }
        if (context.getBizSuccess()) {
            return "OK";
        }
        return StrUtil.isBlank(context.getBizMsg()) ? "FAIL" : "FAIL(" + context.getBizMsg() + ")";
    }

    /**
     * Service 明细：只输出「慢」或「同一次请求内被重复调用」的 Service 方法，
     * 重复调用会带 xN 标记，是发现 N+1 查询最省力的信号。
     */
    private String serviceDetail(TraceContext context) {
        if (context.getServiceCalls().isEmpty()) {
            return "";
        }
        List<TraceContext.ServiceCall> calls = new ArrayList<>(context.getServiceCalls().values());
        calls.sort(Comparator.comparingLong(TraceContext.ServiceCall::getTotalMs).reversed());

        StringBuilder detail = new StringBuilder();
        int printed = 0;
        for (TraceContext.ServiceCall call : calls) {
            boolean repeated = call.getCount() > 1;
            boolean serviceSlow = call.getTotalMs() >= properties.getServiceSlowMs();
            if (!repeated && !serviceSlow) {
                continue;
            }
            if (printed >= Math.max(1, properties.getMaxServiceLines())) {
                detail.append("\n  [SVC] ...(更多明细已省略)");
                break;
            }
            detail.append("\n  [SVC] ").append(call.getName());
            if (repeated) {
                detail.append(" x").append(call.getCount());
            }
            detail.append(" totalMs=").append(call.getTotalMs()).append(" maxMs=").append(call.getMaxMs());
            if (serviceSlow) {
                detail.append(" [SLOW]");
            }
            printed++;
        }
        return detail.toString();
    }

    /* ------------------------------------------------------------------ 通用工具 */

    private ApiTrace findAnnotation(ProceedingJoinPoint point) {
        Method method = ((MethodSignature) point.getSignature()).getMethod();
        ApiTrace annotation = AnnotatedElementUtils.findMergedAnnotation(method, ApiTrace.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), ApiTrace.class);
        }
        if (annotation == null && point.getTarget() != null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(point.getTarget().getClass(), ApiTrace.class);
        }
        return annotation;
    }

    private String resolveApiName(ProceedingJoinPoint point, ApiTrace annotation) {
        if (annotation != null && StrUtil.isNotBlank(annotation.value())) {
            return annotation.value();
        }
        return targetClassName(point) + "#" + point.getSignature().getName();
    }

    private String serviceName(ProceedingJoinPoint point) {
        return targetClassName(point) + "#" + point.getSignature().getName();
    }

    /** 去掉 CGLIB/JDK 代理外壳，拿到真实实现类名，方便直接定位代码 */
    private String targetClassName(ProceedingJoinPoint point) {
        Object target = point.getTarget();
        if (target != null) {
            return ClassUtils.getUserClass(target).getSimpleName();
        }
        return point.getSignature().getDeclaringType().getSimpleName();
    }

    private Long currentUserId() {
        try {
            UserDTO user = UserHolder.getUser();
            return user == null ? null : user.getId();
        } catch (Exception e) {
            return null;
        }
    }

    private ServletRequestAttributes currentRequest() {
        try {
            return (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        } catch (Exception e) {
            return null;
        }
    }

    private long costMs(long startNanos) {
        return (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
    }
}

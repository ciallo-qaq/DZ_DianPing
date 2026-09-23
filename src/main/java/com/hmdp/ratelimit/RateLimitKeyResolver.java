package com.hmdp.ratelimit;

import com.hmdp.dto.UserDTO;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * 按限流维度生成桶 key，并负责从请求中提取客户端 IP。
 *
 * <p>返回 null 表示“无法解析标识”，调用方应当放行并告警：宁可少限流，
 * 也不能因为标识解析失败（例如缺少请求上下文）把业务整体卡死；同时避免把大量无法识别的请求
 * 挤进同一个桶，造成误伤。</p>
 */
@Component
public class RateLimitKeyResolver {

    /** 标识最大长度，防止超长参数把 Redis key 撑大 */
    private static final int MAX_IDENTITY_LENGTH = 64;

    private final RateLimitProperties properties;

    public RateLimitKeyResolver(RateLimitProperties properties) {
        this.properties = properties;
    }

    /**
     * @return 桶 key；无法解析时返回 null
     */
    public String resolve(RateLimitRule rule, ProceedingJoinPoint point, UserDTO user) {
        String ip = clientIp();
        switch (rule.dimension()) {
            case GLOBAL:
                return build(rule, "global");
            case IP:
                return ip == null ? null : build(rule, "ip", ip);
            case USER:
                if (user != null && user.getId() != null) {
                    return build(rule, "user", String.valueOf(user.getId()));
                }
                return ip == null ? null : build(rule, "ip", ip);
            case PARAM: {
                String identity = identity(paramValue(rule, point));
                return identity == null ? null : build(rule, "param", identity);
            }
            case IP_AND_PARAM: {
                String identity = identity(paramValue(rule, point));
                return (ip == null || identity == null) ? null : build(rule, "ip_param", ip, identity);
            }
            case USER_AND_PARAM: {
                String identity = identity(paramValue(rule, point));
                if (identity == null) {
                    return null;
                }
                if (user != null && user.getId() != null) {
                    return build(rule, "user_param", String.valueOf(user.getId()), identity);
                }
                return ip == null ? null : build(rule, "ip_param", ip, identity);
            }
            default:
                return null;
        }
    }

    /**
     * 提取客户端 IP。优先级：
     * 1) X-Real-IP（由 Nginx 覆写，最可信）；
     * 2) X-Forwarded-For 的最后一跳（由最近的代理追加，比第一跳更难伪造）；
     * 3) RemoteAddr。
     *
     * <p>X-Forwarded-For 整体是客户端可伪造的，因此只作为兜底，并且统一做字符白名单过滤，
     * 防止伪造值里带冒号、空格、大括号污染桶 key。</p>
     */
    public String clientIp() {
        ServletRequestAttributes attributes = currentRequest();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String realIp = firstToken(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return sanitize(realIp);
        }
        String forwarded = lastToken(request.getHeader("X-Forwarded-For"));
        if (forwarded != null) {
            return sanitize(forwarded);
        }
        return sanitize(request.getRemoteAddr());
    }

    /**
     * 桶 key 结构：{prefix}:{规则名}:{维度标签}|{标识1}|{标识2}。
     *
     * <p>标识之间用 '|' 分隔而不是 ':'，因为 {@link #sanitize(String)} 会把 '|' 替换成 '_'，
     * 攻击者无法通过伪造 header 里的分隔符把请求"搬"到另一条规则的桶里。</p>
     */
    private String build(RateLimitRule rule, String tag, String... values) {
        String prefix = properties.getKeyPrefix();
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = "ratelimit";
        }
        StringBuilder builder = new StringBuilder(prefix.trim())
                .append(':').append(rule.key()).append(':').append(tag);
        if (values.length > 0) {
            builder.append('|');
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    builder.append('|');
                }
                builder.append(values[i]);
            }
        }
        return builder.toString();
    }

    /**
     * 取方法参数值：下标 > 注解声明的参数名 > 形参名 > 请求体属性名。
     */
    private Object paramValue(RateLimitRule rule, ProceedingJoinPoint point) {
        Object[] args = point.getArgs();
        if (args == null || args.length == 0) {
            return null;
        }
        if (rule.paramIndex() >= 0) {
            return rule.paramIndex() < args.length ? args[rule.paramIndex()] : null;
        }
        String name = rule.paramName();
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        name = name.trim();
        MethodSignature signature = (MethodSignature) point.getSignature();

        // 1. @RequestParam / @PathVariable 上声明的名字（不依赖编译期参数名，最稳妥）
        Annotation[][] annotations = signature.getMethod().getParameterAnnotations();
        for (int i = 0; i < args.length && i < annotations.length; i++) {
            for (Annotation annotation : annotations[i]) {
                if (annotation instanceof RequestParam && name.equals(((RequestParam) annotation).value())) {
                    return args[i];
                }
                if (annotation instanceof PathVariable && name.equals(((PathVariable) annotation).value())) {
                    return args[i];
                }
            }
        }

        // 2. 形参名（需要编译期保留参数名，取不到时继续往下走）
        String[] parameterNames = signature.getParameterNames();
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                if (name.equals(parameterNames[i])) {
                    return args[i];
                }
            }
        }

        // 3. 请求体对象的属性，例如 LoginFormDTO#phone
        for (Object arg : args) {
            if (arg == null || isSimpleValue(arg)) {
                continue;
            }
            Object value = readProperty(arg, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean isSimpleValue(Object value) {
        return value instanceof CharSequence || value instanceof Number || value instanceof Boolean;
    }

    private Object readProperty(Object target, String property) {
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        try {
            Method getter = target.getClass().getMethod("get" + suffix);
            return getter.invoke(target);
        } catch (Exception ignore) {
            // 忽略：继续尝试 isXxx 形式
        }
        try {
            Method getter = target.getClass().getMethod("is" + suffix);
            return getter.invoke(target);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String identity(Object value) {
        return value == null ? null : sanitize(String.valueOf(value));
    }

    private String firstToken(String header) {
        return token(header, true);
    }

    private String lastToken(String header) {
        return token(header, false);
    }

    private String token(String header, boolean first) {
        if (header == null || header.trim().isEmpty()) {
            return null;
        }
        String[] parts = header.split(",");
        if (first) {
            for (String part : parts) {
                if (part != null && !part.trim().isEmpty()) {
                    return part.trim();
                }
            }
            return null;
        }
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i] != null && !parts[i].trim().isEmpty()) {
                return parts[i].trim();
            }
        }
        return null;
    }

    /** 只保留安全字符，避免伪造的 header 污染桶 key；'|' 不在白名单内，因此它是不可伪造的分隔符 */
    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(Math.min(trimmed.length(), MAX_IDENTITY_LENGTH));
        for (int i = 0; i < trimmed.length() && builder.length() < MAX_IDENTITY_LENGTH; i++) {
            char c = trimmed.charAt(i);
            boolean safe = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '.' || c == ':' || c == '_' || c == '-';
            builder.append(safe ? c : '_');
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private ServletRequestAttributes currentRequest() {
        try {
            return (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        } catch (Exception e) {
            return null;
        }
    }
}

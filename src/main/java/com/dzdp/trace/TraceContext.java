package com.dzdp.trace;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次请求的采集上下文。
 *
 * <p>生命周期与一次 HTTP 请求一致：入口切面创建 -> Service 切面往里累加耗时 -> 入口切面输出日志并清理。
 * 只在当前请求线程内使用，不涉及跨线程共享，因此这里不做并发控制。</p>
 */
@Data
public class TraceContext {

    /** 链路标识，由入口切面生成，便于把同一次请求的多行日志串起来 */
    private String traceId;

    /** 接口名，默认「类名#方法名」 */
    private String apiName;

    /** 请求路径模板，例如 /voucher-order/seckill/{id} */
    private String uri;

    /** 请求类型 GET/POST/PUT/DELETE */
    private String httpMethod;

    /** 已脱敏并截断后的请求参数 */
    private String params = "-";

    /** 当前登录用户 id，取自项目已有的 UserHolder，未登录为 null */
    private Long userId;

    /** 请求总耗时（切面进入 -> 方法返回/抛异常） */
    private long totalCostMs;

    /** 被注解的 Controller 方法自身耗时 */
    private long controllerCostMs;

    /** 本次请求内所有 Service 调用耗时之和 */
    private long serviceTotalMs;

    /** HTTP 状态码 */
    private int httpStatus = 200;

    /** 业务返回结果：Result.success() 为 true，失败为 false，异常为 null */
    private Boolean bizSuccess;

    /** 业务失败原因，取 Result.errorMsg */
    private String bizMsg;

    /** 请求过程中抛出的异常，用于异常监控 */
    private Throwable exception;

    /** Service 调用聚合：方法名 -> 调用次数/累计耗时/最大耗时 */
    private final Map<String, ServiceCall> serviceCalls = new LinkedHashMap<>();

    /**
     * 记录一次 Service 调用。
     * 同一个方法在一次请求内被多次调用会合并计数，日志里会显示 xN，
     * 这是快速发现 N+1 查询的信号。
     */
    public void recordService(String name, long costMs) {
        serviceTotalMs += costMs;
        ServiceCall call = serviceCalls.get(name);
        if (call == null) {
            call = new ServiceCall();
            call.setName(name);
            serviceCalls.put(name, call);
        }
        call.setCount(call.getCount() + 1);
        call.setTotalMs(call.getTotalMs() + costMs);
        call.setMaxMs(Math.max(call.getMaxMs(), costMs));
    }

    /** 单个 Service 方法的调用统计 */
    @Data
    public static class ServiceCall {
        private String name;
        private int count;
        private long totalMs;
        private long maxMs;
    }
}

package com.dzdp.trace;

/**
 * 采集上下文的线程绑定工具。
 *
 * <p>注意：Tomcat 线程会被复用，入口切面必须在 finally 中调用 {@link #clear()}，
 * 否则会出现「上一次请求的 Service 耗时串到下一次请求日志里」的问题。</p>
 */
public final class TraceContextHolder {

    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    public static void set(TraceContext context) {
        CONTEXT.set(context);
    }

    /** Service 切面通过它拿到当前请求上下文；不在接口调用链上时返回 null */
    public static TraceContext getCurrent() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}

package com.dzdp.service;

/**
 * 仅用于验证 {@link com.dzdp.trace.ApiTraceAspect} 的 Service 计时切点。
 *
 * <p>位于 com.dzdp.service 包下，命中切面的 com.dzdp.service..* 包表达式。</p>
 */
public class TraceDemoService {

    public String doWork() {
        return "ok";
    }

    public String slowWork() throws InterruptedException {
        Thread.sleep(60L);
        return "ok";
    }
}

package com.hmdp.service;

/**
 * 仅用于验证 {@link com.hmdp.trace.ApiTraceAspect} 的 Service 计时切点。
 *
 * <p>刻意放在 com.hmdp.service 包下，才能命中切面的 com.hmdp.service..* 包表达式。</p>
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

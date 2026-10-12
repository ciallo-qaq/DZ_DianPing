package com.dzdp.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 开发期接口性能采集注解。
 *
 * <p>整个方案只有这一个注解，直接打在 Controller 类（推荐）或方法上即可：
 * Controller 耗时、请求参数、返回码、异常由入口切面采集，
 * Service 耗时由切面按包路径自动采集，不需要在 Service 上再加注解。</p>
 *
 * <p>它面向的是"开发期调优定位"，不是生产级 APM，默认在 application.yaml 中关闭，
 * 仅 dev 环境打开。</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiTrace {

    /** 接口名，留空时自动使用「Controller 类名#方法名」 */
    String value() default "";

    /** 是否采集请求参数，文件上传等场景可置为 false 减少噪音 */
    boolean args() default true;

    /** 本接口的慢接口阈值(毫秒)，0 表示使用全局配置 dev-trace.slow-ms */
    long slowMs() default 0L;
}

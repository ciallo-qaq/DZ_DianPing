package com.dzdp.trace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 采集开关与阈值配置，对应 application.yaml 中的 dev-trace 前缀。
 * 全部提供默认值，开发期零配置即可使用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "dev-trace")
public class TraceProperties {

    /** 总开关，默认关闭，仅 dev 环境打开 */
    private boolean enabled = false;

    /** 全局慢接口阈值(毫秒)，超过则用 WARN 级别输出 */
    private long slowMs = 200L;

    /** Service 明细输出阈值(毫秒)，低于该值且只调用一次的 Service 不单独占一行 */
    private long serviceSlowMs = 50L;

    /** 单个参数值最大长度，超出截断，避免大对象刷屏 */
    private int maxArgLength = 200;

    /** 单次请求最多采集的参数个数 */
    private int maxParams = 20;

    /** 单次请求最多输出的 Service 明细行数 */
    private int maxServiceLines = 10;

    /** 需要脱敏的字段名（大小写不敏感），命中后值替换为 *** */
    private List<String> maskKeys = new ArrayList<>(
            Arrays.asList("password", "token", "code", "phone", "authorization", "idCard"));
}

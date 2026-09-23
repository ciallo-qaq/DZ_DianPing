package com.hmdp.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 限流开关与白名单，对应 application.yaml 中的 rate-limit 前缀。
 *
 * <p>默认开启且白名单为空：生产环境不做任何额外配置就是“全部限流”的安全状态，
 * 本地开发/压测需要时再在 dev profile 中配置白名单。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    /** 总开关，默认开启 */
    private boolean enabled = true;

    /** 桶 key 前缀，便于在 Redis 中按前缀检索与清理 */
    private String keyPrefix = "ratelimit";

    /** 白名单 IP：命中的请求完全不做限流，仅用于压测、运维与本地开发 */
    private List<String> whitelistIps = new ArrayList<>();

    public boolean isWhitelisted(String ip) {
        if (ip == null || whitelistIps == null || whitelistIps.isEmpty()) {
            return false;
        }
        for (String item : whitelistIps) {
            if (item != null && ip.equals(item.trim())) {
                return true;
            }
        }
        return false;
    }
}

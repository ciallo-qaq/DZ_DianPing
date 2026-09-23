package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.ratelimit.RateLimit;
import com.hmdp.ratelimit.RateLimitDimension;
import com.hmdp.ratelimit.RateLimitRule;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.trace.ApiTrace;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;


@ApiTrace
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 秒杀下单限流（落在 Lua 库存校验之前，命中限流的请求不会进入 Lua 与 MQ）：
     * 1) 用户维度 capacity=1：令牌桶退化为“每次下单后等 2 秒”，抑制单账号高频刷单；
     * 2) IP 维度 capacity=50 / 30 每秒：抑制单机脚本刷接口与黄牛集群。
     *
     * <p>两条规则都 failOpen=false：Redis 不可用时秒杀本身也无法完成库存校验，
     * 此时快速失败比放流量进来更安全。</p>
     */
    @PostMapping("seckill/{id}")
    @RateLimit({
            @RateLimitRule(key = "seckill:user", dimension = RateLimitDimension.USER,
                    capacity = 1, refillTokens = 1, refillPeriodMillis = 2_000L, failOpen = false,
                    message = "操作过于频繁，请稍后再试"),
            @RateLimitRule(key = "seckill:ip", dimension = RateLimitDimension.IP,
                    capacity = 50, refillTokens = 30, refillPeriodMillis = 1_000L, failOpen = false,
                    message = "当前网络请求过于频繁，请稍后再试")
    })
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}

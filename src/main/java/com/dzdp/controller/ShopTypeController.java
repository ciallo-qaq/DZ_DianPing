package com.dzdp.controller;


import com.dzdp.dto.Result;
import com.dzdp.ratelimit.RateLimit;
import com.dzdp.ratelimit.RateLimitDimension;
import com.dzdp.ratelimit.RateLimitRule;
import com.dzdp.service.IShopTypeService;
import com.dzdp.trace.ApiTrace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;


@ApiTrace
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("list")
    @RateLimit(@RateLimitRule(key = "shop:type:list", dimension = RateLimitDimension.IP,
            capacity = 60, refillTokens = 40, refillPeriodMillis = 1_000L))
    public Result queryTypeList() {
        return typeService.querySort();
    }
}

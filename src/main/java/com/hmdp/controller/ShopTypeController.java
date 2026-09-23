package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.ratelimit.RateLimit;
import com.hmdp.ratelimit.RateLimitDimension;
import com.hmdp.ratelimit.RateLimitRule;
import com.hmdp.service.IShopTypeService;
import com.hmdp.trace.ApiTrace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;


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

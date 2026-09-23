package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.ratelimit.RateLimit;
import com.hmdp.ratelimit.RateLimitDimension;
import com.hmdp.ratelimit.RateLimitRule;
import com.hmdp.service.IShopService;
import com.hmdp.trace.ApiTrace;
import com.hmdp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;


@ApiTrace
@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;


    @GetMapping("/{id}")
    @RateLimit(@RateLimitRule(key = "shop:detail", dimension = RateLimitDimension.IP,
            capacity = 30, refillTokens = 20, refillPeriodMillis = 1_000L))
    public Result queryShopById(@PathVariable("id") Long id) throws InterruptedException {
        return shopService.queryById(id);
    }

    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id
        return Result.ok(shop.getId());
    }


    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        // 写入数据库

        return shopService.update(shop);
    }

    @GetMapping("/of/type")
    @RateLimit(@RateLimitRule(key = "shop:type", dimension = RateLimitDimension.IP,
            capacity = 30, refillTokens = 20, refillPeriodMillis = 1_000L))
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x",required = false) Double x,
            @RequestParam(value = "y",required = false) Double y
    ) {
        return shopService.queryShopByType(typeId,current,x,y);
    }


    @GetMapping("/of/name")
    @RateLimit(@RateLimitRule(key = "shop:name", dimension = RateLimitDimension.IP,
            capacity = 20, refillTokens = 10, refillPeriodMillis = 1_000L))
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        return Result.ok(page.getRecords());
    }
}

package com.dzdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dzdp.dto.Result;
import com.dzdp.entity.Shop;
import com.dzdp.ratelimit.RateLimit;
import com.dzdp.ratelimit.RateLimitDimension;
import com.dzdp.ratelimit.RateLimitRule;
import com.dzdp.service.IShopService;
import com.dzdp.trace.ApiTrace;
import com.dzdp.utils.SystemConstants;
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
        // 根据店名分页查询
        // 注意 likeRight 生成的是 "name LIKE '关键字%'"，才能命中 idx_name 索引；
        // 原来的 like 生成 "name LIKE '%关键字%'"，前置通配符会让索引彻底失效。
        // 代价是变成前缀匹配，如果业务需要中间匹配，请改用全文索引(ngram)或 ES。
        Page<Shop> page = shopService.query()
                .likeRight(StrUtil.isNotBlank(name), "name", name)
                .orderByDesc("id")
                .page(new Page<>(SystemConstants.safeCurrentPage(current), SystemConstants.MAX_PAGE_SIZE));

        return Result.ok(page.getRecords());
    }
}

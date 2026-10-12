package com.dzdp.controller;


import com.dzdp.dto.Result;
import com.dzdp.entity.Voucher;
import com.dzdp.service.IVoucherService;
import com.dzdp.trace.ApiTrace;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;


@ApiTrace
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;


    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }


    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }


    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}

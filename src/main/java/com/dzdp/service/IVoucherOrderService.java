package com.dzdp.service;

import com.dzdp.dto.Result;
import com.dzdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    void handleVoucherOrder(VoucherOrder voucherOrder);
}

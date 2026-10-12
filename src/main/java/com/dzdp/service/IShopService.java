package com.dzdp.service;

import com.dzdp.dto.Result;
import com.dzdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopService extends IService<Shop> {
    Result queryById(Long id) throws InterruptedException;

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}

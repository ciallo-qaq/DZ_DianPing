package com.dzdp.service;

import com.dzdp.dto.Result;
import com.dzdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopTypeService extends IService<ShopType> {

    Result querySort();
}

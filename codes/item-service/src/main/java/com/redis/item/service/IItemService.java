package com.redis.item.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.redis.item.pojo.Item;

public interface IItemService extends IService<Item> {
    void saveItem(Item item);
}

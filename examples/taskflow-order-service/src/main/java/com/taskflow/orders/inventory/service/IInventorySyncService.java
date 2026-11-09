package com.taskflow.orders.inventory.service;

import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Request;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Result;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Stock;

public interface IInventorySyncService {
    Result insert(Request request);
    Stock select(long warehouseId, long skuId);
}

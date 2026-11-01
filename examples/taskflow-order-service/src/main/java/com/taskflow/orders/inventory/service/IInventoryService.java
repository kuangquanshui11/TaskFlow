package com.taskflow.orders.inventory.service;

import com.taskflow.orders.inventory.model.Stock;

public interface IInventoryService {
    Stock insert(long skuId, long available);
    Stock select(long skuId);
    void hold(String orderId, long skuId, int quantity, String requestId);
    void settle(String orderId, long skuId, int quantity, boolean release, String requestId);
    void publishPending();
}

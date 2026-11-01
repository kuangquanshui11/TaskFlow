package com.taskflow.orders.order.service;

import com.taskflow.orders.order.dto.OrderProtocol.*;

public interface IOrderService {
    Order insert(Create request);
    Order updatePayment(String id);
    Detail select(String id);
    BatchResult updateTimeout(Batch batch, String requestId);
}

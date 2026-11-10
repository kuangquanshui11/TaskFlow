package com.taskflow.orders.payment.service;

import com.taskflow.orders.order.dto.OrderProtocol.Order;
import com.taskflow.orders.payment.dto.PaymentProtocol.*;
import com.taskflow.orders.payment.model.PaymentWork.Kind;
import com.taskflow.orders.payment.service.IWechatPayClient.Trade;

public interface IOrderPaymentService {
    Status insert(String orderId);
    Status select(String orderId);
    Status retry(String orderId, Kind kind);
    void notifyPayment(Notification notification);
    void recordTrade(Trade trade);
    void checkSimulationAllowed(String orderId);
    void enqueueInventoryRelease(Order order);
}

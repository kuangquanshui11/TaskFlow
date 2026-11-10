package com.taskflow.orders.payment.model;

import java.time.Instant;

public record PaymentOrder(String orderId, String tradeNo, String appId, String merchantId,
                           String description, int amountCents, String paymentState, String codeUrl,
                           String transactionId, Instant paidAt, String refundState, String refundId) {
    public String refundNo() {
        return "late-" + tradeNo;
    }
}

package com.taskflow.orders.payment.dto;

import java.time.Instant;
import com.taskflow.orders.payment.model.PaymentWork;
import jakarta.validation.constraints.NotNull;

public final class PaymentProtocol {
    private PaymentProtocol() { }

    public record Start(@NotNull java.util.UUID orderId) { }
    public record Retry(@NotNull java.util.UUID orderId, @NotNull PaymentWork.Kind kind) { }
    public record Status(String orderId, String tradeNo, int amountCents, Instant expiresAt,
                         String paymentState, String codeUrl, String transactionId, Instant paidAt,
                         String refundNo, String refundState, String refundId,
                         Delivery payment, Delivery refund, Delivery inventoryRelease) { }
    public record Delivery(String state, int attempts, String lastError, Instant nextAt) { }
    public record Notification(String serial, String timestamp, String nonce, String signature, String body) { }
    public record Release(String eventId, String orderId, long skuId, int quantity, String reason) { }
}

package com.taskflow.orders.payment.service;

import java.time.Instant;
import com.taskflow.orders.payment.dto.PaymentProtocol.Notification;
import com.taskflow.orders.payment.model.PaymentOrder;

public interface IWechatPayClient {
    String prepay(PaymentOrder payment, Instant expiresAt);
    Trade query(PaymentOrder payment);
    void close(PaymentOrder payment);
    void refund(PaymentOrder payment);
    Refund queryRefund(PaymentOrder payment);
    Trade decode(Notification notification);

    record Trade(String appId, String merchantId, String tradeNo, String transactionId, String tradeType,
                 String state, Instant paidAt, Integer amountCents, String currency) { }
    record Refund(String refundNo, String tradeNo, String transactionId, String refundId, String state,
                  Long amountCents, Long totalCents, String currency) { }

    class RemoteFailure extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public RemoteFailure(String code, boolean retryable) {
            super(code);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }
}

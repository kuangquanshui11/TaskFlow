package com.taskflow.orders.payment.model;

import java.time.Instant;

public record PaymentWork(String orderId, Kind kind, String state, int attempts, Instant nextAt,
                          Instant stopAt, String claimToken, String lastError) {
    public enum Kind { PAYMENT, REFUND, RELEASE }
}

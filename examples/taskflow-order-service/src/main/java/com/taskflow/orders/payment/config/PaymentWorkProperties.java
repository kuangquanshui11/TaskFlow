package com.taskflow.orders.payment.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order.payment-work")
public record PaymentWorkProperties(boolean inventoryReleaseEnabled, URI inventoryReleaseUrl, String inventoryReleaseToken,
                                    Duration inventoryConnectTimeout, Duration inventoryRequestTimeout,
                                    Duration lease, Duration retryDelay, Duration paymentPollDelay, Duration refundPollDelay,
                                    Duration reconciliationWindow, int maxFailures, int batchSize, boolean scanEnabled) {
    public PaymentWorkProperties {
        if (lease.compareTo(inventoryConnectTimeout.plus(inventoryRequestTimeout)) <= 0
                || retryDelay.isNegative() || retryDelay.isZero() || paymentPollDelay.isNegative() || paymentPollDelay.isZero()
                || refundPollDelay.isNegative() || refundPollDelay.isZero() || reconciliationWindow.isNegative()
                || reconciliationWindow.isZero() || maxFailures < 1 || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Invalid payment work settings");
        }
    }
}

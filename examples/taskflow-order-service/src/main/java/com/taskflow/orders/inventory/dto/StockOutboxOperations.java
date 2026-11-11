package com.taskflow.orders.inventory.dto;

import java.time.Instant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class StockOutboxOperations {
    private StockOutboxOperations() { }

    public record Failure(long id, String eventId, long generation, long skuId, int attempts,
                          Instant detectedAt, Instant lastFailedAt, String errorCode,
                          Instant retryRequestedAt, String retryReason, Instant resolvedAt) { }

    public record RetryRequest(@NotNull @PositiveOrZero Long generation,
                               @NotBlank @Size(max = 250) String reason) { }

    public record RetryReceipt(String eventId, long generation, long queuedGeneration,
                               Instant queuedAt, String reason) { }
}

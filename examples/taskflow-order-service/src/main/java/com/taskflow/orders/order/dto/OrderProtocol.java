package com.taskflow.orders.order.dto;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;

public final class OrderProtocol {
    private OrderProtocol() { }
    public record Create(@NotBlank @Size(max=100) String requestKey, @Positive long skuId, @Min(1) @Max(1000000) int quantity) { }
    public record Payment(@NotBlank @Size(max=36) String orderId) { }
    public record Batch(@Min(1) @Max(500) int batchSize, Instant cutoff, Instant afterExpiresAt,
                        @Size(max=36) String afterId) { }
    public record BatchResult(int scanned, int closed, int skipped, int failed, Instant cutoff,
                              Instant afterExpiresAt, String afterId) { }
    public record Order(String id, String requestKey, long skuId, int quantity, String state,
                        Instant createdAt, Instant expiresAt, Instant paidAt, Instant closedAt, String closeReason) { }
    public record Ledger(String orderId, long skuId, String operation, int quantity, String requestId, Instant createdAt) { }
    public record Detail(Order order, List<Ledger> ledger) { }
}

package com.taskflow.orders.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class InventorySyncProtocol {
    private InventorySyncProtocol() { }

    public record Request(@NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9][a-z0-9:._-]*") String requestKey,
                          @Positive long warehouseId) { }

    public enum Outcome { APPLIED, UNCHANGED, STALE }

    public record Result(String id, long warehouseId, long sourceVersion, Outcome outcome, int updatedCount) { }

    public record Stock(long warehouseId, long skuId, long quantity, long sourceVersion) { }
}

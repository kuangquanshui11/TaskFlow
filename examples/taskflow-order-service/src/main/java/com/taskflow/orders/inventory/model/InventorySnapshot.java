package com.taskflow.orders.inventory.model;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record InventorySnapshot(@Positive long warehouseId, @Positive long sourceVersion,
                                @NotNull @Size(max = 1000) List<@NotNull @Valid Item> items) {
    public record Item(@Positive long skuId, @NotNull @PositiveOrZero @Max(1_000_000_000) Long quantity) { }
}

package com.taskflow.orders.report.dto;

import jakarta.validation.constraints.*;

public final class SalesInput {
    private SalesInput() { }

    public record Shop(@Positive long shopId, @NotBlank @Size(max=100) String name) { }

    public record CatalogProduct(@Positive long skuId, @Positive long shopId,
                                 @NotBlank @Size(max=100) String name,
                                 @Min(1) @Max(Long.MAX_VALUE / 1_000_000) long unitPriceCents,
                                 @NotBlank @Size(max=64) String catalogSource, @NotNull @Positive Long catalogVersion) { }

    public record Backfill(@NotBlank @Pattern(regexp="[a-f0-9-]{36}") String orderId, @Positive long shopId,
                           @NotBlank @Size(max=100) String productName, @Positive long unitPriceCents,
                           @NotBlank @Size(max=64) String sourceSystem,
                           @NotBlank @Size(max=100) String sourceReference) { }
}

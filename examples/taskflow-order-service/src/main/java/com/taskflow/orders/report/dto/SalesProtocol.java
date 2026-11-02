package com.taskflow.orders.report.dto;

import java.time.*;
import java.util.List;
import jakarta.validation.constraints.*;

public final class SalesProtocol {
    private SalesProtocol() { }
    public record Generate(@NotBlank @Size(max=100) String requestKey, @Positive long shopId,
                           LocalDate businessDate, @NotBlank @Size(max=100) String timeZone) { }
    public record Product(long skuId, long shopId, String name, long unitPriceCents) { }
    public record Request(String requestKey, long shopId, LocalDate requestedDate, LocalDate businessDate, String timeZone) { }
    public record Report(String id, long shopId, LocalDate businessDate, String timeZone,
                         long orderCount, long totalQuantity, long totalAmountCents, Instant generatedAt) { }
    public record Item(long skuId, String productName, long orderCount, long totalQuantity, long totalAmountCents) { }
    public record Detail(Report report, List<Item> items) { }
    public record Page(List<Report> items, long total, int page, int pageSize) { }
    public record Export(String filename, String content) { }
    public record Sample(@NotBlank @Pattern(regexp="[a-z][a-z0-9-]{0,31}") String dataset,
                         @NotNull LocalDate startDate, @Min(1) @Max(90) int days,
                         @Min(1) @Max(10000) int orderCount, long seed) { }
}

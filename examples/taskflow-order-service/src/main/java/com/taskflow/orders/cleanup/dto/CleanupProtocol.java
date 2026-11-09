package com.taskflow.orders.cleanup.dto;

import java.time.Instant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class CleanupProtocol {
    public static final int MIN_RETENTION_DAYS = 90;
    public static final int MAX_RETENTION_DAYS = 3650;
    public static final int MAX_BATCH_SIZE = 500;
    public static final int MAX_BATCHES = 10;

    private CleanupProtocol() { }

    public record Request(@NotNull @Pattern(regexp = "[a-z0-9][a-z0-9._:-]{0,99}") String requestKey,
                          @NotNull @Min(MIN_RETENTION_DAYS) @Max(MAX_RETENTION_DAYS) Integer retentionDays,
                          @NotNull @Min(1) @Max(MAX_BATCH_SIZE) Integer batchSize,
                          @NotNull @Min(1) @Max(MAX_BATCHES) Integer batchNumber) { }

    public record Preview(Instant cutoff, int eligibleCount, boolean hasMore) { }

    public record Batch(Instant cutoff, int batchNumber, int deletedCount, boolean hasMore) { }
}

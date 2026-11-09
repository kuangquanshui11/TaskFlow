package com.taskflow.orders.cleanup.controller;

import com.taskflow.orders.cleanup.dto.CleanupProtocol.Batch;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Preview;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Request;
import com.taskflow.orders.cleanup.service.ICleanupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.taskflow.orders.cleanup.dto.CleanupProtocol.MAX_BATCH_SIZE;
import static com.taskflow.orders.cleanup.dto.CleanupProtocol.MAX_RETENTION_DAYS;
import static com.taskflow.orders.cleanup.dto.CleanupProtocol.MIN_RETENTION_DAYS;

@RestController
@RequestMapping("/internal/v1/expired-data")
@ConditionalOnProperty(name = "order.cleanup.enabled", havingValue = "true")
public class CleanupController {
    private final ICleanupService cleanup;

    public CleanupController(ICleanupService cleanup) {
        this.cleanup = cleanup;
    }

    @GetMapping("/select")
    public Preview select(@RequestParam @Min(MIN_RETENTION_DAYS) @Max(MAX_RETENTION_DAYS) int retentionDays,
                          @RequestParam @Min(1) @Max(MAX_BATCH_SIZE) int batchSize) {
        return cleanup.select(retentionDays, batchSize);
    }

    @PostMapping("/delete")
    public Batch delete(@Valid @RequestBody Request request) {
        return cleanup.delete(request);
    }
}

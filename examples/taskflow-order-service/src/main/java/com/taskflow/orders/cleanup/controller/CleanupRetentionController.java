package com.taskflow.orders.cleanup.controller;

import com.taskflow.orders.cleanup.dto.CleanupProtocol.RetentionPreview;
import com.taskflow.orders.cleanup.service.ICleanupRetentionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/order-operations/cleanup-retention")
@ConditionalOnProperty(name = "order.operations.enabled", havingValue = "true")
public class CleanupRetentionController {
    private final ICleanupRetentionService retention;

    public CleanupRetentionController(ICleanupRetentionService retention) {
        this.retention = retention;
    }

    @GetMapping("/select")
    public RetentionPreview select() {
        return retention.preview();
    }
}

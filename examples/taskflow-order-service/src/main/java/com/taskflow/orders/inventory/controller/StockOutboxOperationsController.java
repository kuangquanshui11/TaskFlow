package com.taskflow.orders.inventory.controller;

import java.util.List;
import java.util.UUID;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.Failure;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.RetryReceipt;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.RetryRequest;
import com.taskflow.orders.inventory.service.IStockOutboxOperationsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/order-operations/stock-outbox-failures")
@ConditionalOnProperty(name = "order.operations.enabled", havingValue = "true")
public class StockOutboxOperationsController {
    private final IStockOutboxOperationsService operations;

    public StockOutboxOperationsController(IStockOutboxOperationsService operations) {
        this.operations = operations;
    }

    @GetMapping("/select")
    public List<Failure> select(@RequestParam(required = false) UUID eventId,
                                @RequestParam(defaultValue = "0") @Min(0) long afterId,
                                @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
                                @RequestParam(defaultValue = "false") boolean includeResolved) {
        return operations.select(eventId == null ? null : eventId.toString(), afterId, limit, includeResolved);
    }

    @PostMapping("/{eventId}/updateRetry")
    public RetryReceipt updateRetry(@PathVariable UUID eventId, @Valid @RequestBody RetryRequest request) {
        return operations.retry(eventId.toString(), request);
    }
}

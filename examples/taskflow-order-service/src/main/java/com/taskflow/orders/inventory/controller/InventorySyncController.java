package com.taskflow.orders.inventory.controller;

import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Request;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Result;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Status;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Stock;
import com.taskflow.orders.inventory.service.IInventorySyncService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/inventory-sync")
@ConditionalOnProperty(name = "order.inventory-sync.enabled", havingValue = "true")
public class InventorySyncController {
    private final IInventorySyncService inventorySync;

    public InventorySyncController(IInventorySyncService inventorySync) {
        this.inventorySync = inventorySync;
    }

    @PostMapping("/insert")
    public Result insert(@Valid @RequestBody Request request) {
        return inventorySync.insert(request);
    }

    @GetMapping("/select")
    public Stock select(@Positive @RequestParam long warehouseId, @Positive @RequestParam long skuId) {
        return inventorySync.select(warehouseId, skuId);
    }

    @GetMapping("/selectStatus")
    public Status selectStatus(@Positive @RequestParam long warehouseId) {
        return inventorySync.selectStatus(warehouseId);
    }
}

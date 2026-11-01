package com.taskflow.orders.inventory.controller;

import com.taskflow.orders.inventory.model.Stock;
import com.taskflow.orders.inventory.service.IInventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {
    private final IInventoryService inventory;
    public InventoryController(IInventoryService inventory) { this.inventory=inventory; }
    public record InitialStock(@Positive long skuId, @Min(0) @Max(1000000000) long available) { }
    @PostMapping("/insert") public Stock insert(@Valid @RequestBody InitialStock request) { return inventory.insert(request.skuId(),request.available()); }
    @GetMapping("/select") public Stock select(@RequestParam long skuId) { return inventory.select(skuId); }
}

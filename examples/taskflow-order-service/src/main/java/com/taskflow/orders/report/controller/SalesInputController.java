package com.taskflow.orders.report.controller;

import com.taskflow.orders.report.dto.SalesInput.*;
import com.taskflow.orders.report.service.ISalesInputService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/sales-inputs")
public class SalesInputController {
    private final ISalesInputService inputs;

    public SalesInputController(ISalesInputService inputs) {
        this.inputs = inputs;
    }

    @PostMapping("/shops/insert")
    public Shop insertShop(@Valid @RequestBody Shop request) { return inputs.insertShop(request); }

    @PostMapping("/products/update")
    public CatalogProduct updateProduct(@Valid @RequestBody CatalogProduct request) { return inputs.updateProduct(request); }

    @PostMapping("/snapshots/insert")
    public Backfill insertSnapshot(@Valid @RequestBody Backfill request) { return inputs.insertBackfill(request); }
}

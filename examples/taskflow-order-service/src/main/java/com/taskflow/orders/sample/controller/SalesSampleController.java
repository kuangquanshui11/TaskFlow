package com.taskflow.orders.sample.controller;
import com.taskflow.orders.report.dto.SalesProtocol.Sample;
import com.taskflow.orders.sample.service.ISalesSampleService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="order.samples-enabled",havingValue="true")
@RequestMapping("/api/v1/sales-samples")
public class SalesSampleController {
    private final ISalesSampleService samples;
    public SalesSampleController(ISalesSampleService samples) { this.samples=samples; }
    @PostMapping("/insert") public Sample insert(@Valid @RequestBody Sample sample) { return samples.insert(sample); }
}

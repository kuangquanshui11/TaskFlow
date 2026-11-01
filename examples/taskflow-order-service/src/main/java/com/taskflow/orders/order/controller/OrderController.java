package com.taskflow.orders.order.controller;

import com.taskflow.orders.order.dto.OrderProtocol.*;
import com.taskflow.orders.order.service.IOrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {
    private final IOrderService orders;
    public OrderController(IOrderService orders) { this.orders=orders; }
    @PostMapping("/api/v1/orders/insert") public Order insert(@Valid @RequestBody Create request) { return orders.insert(request); }
    @GetMapping("/api/v1/orders/select") public Detail select(@RequestParam String id) { return orders.select(id); }
    @PostMapping("/api/v1/orders/updatePayment") public Order payment(@Valid @RequestBody Payment request) { return orders.updatePayment(request.orderId()); }
    @PostMapping("/internal/v1/orders/updateTimeout") public BatchResult timeout(@Valid @RequestBody Batch request,
            @RequestHeader("X-Request-Id") String requestId) { return orders.updateTimeout(request,requestId); }
}

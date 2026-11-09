package com.taskflow.worker.remote.client;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "taskflow-order-service", contextId = "inventorySync", url = "${taskflow.order.url}",
        configuration = OrderTimeoutRemote.Timeouts.class)
public interface InventorySyncRemote {
    record Request(String requestKey, long warehouseId) { }

    record Result(String id, long warehouseId, long sourceVersion, String outcome, int updatedCount) { }

    @PostMapping("/internal/v1/inventory-sync/insert")
    Result insert(@RequestBody Request request, @RequestHeader Map<String, String> headers);
}

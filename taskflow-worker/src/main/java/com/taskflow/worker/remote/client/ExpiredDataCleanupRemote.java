package com.taskflow.worker.remote.client;

import java.time.Instant;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "taskflow-order-service", contextId = "expiredDataCleanup", url = "${taskflow.order.url}",
        configuration = OrderTimeoutRemote.Timeouts.class)
public interface ExpiredDataCleanupRemote {
    record Request(String requestKey, int retentionDays, int batchSize, int batchNumber) { }

    record Preview(Instant cutoff, int eligibleCount, boolean hasMore) { }

    record Batch(Instant cutoff, int batchNumber, int deletedCount, boolean hasMore) { }

    @GetMapping("/internal/v1/expired-data/select")
    Preview select(@RequestParam("retentionDays") int retentionDays, @RequestParam("batchSize") int batchSize,
                   @RequestHeader Map<String, String> headers);

    @PostMapping("/internal/v1/expired-data/delete")
    Batch delete(@RequestBody Request request, @RequestHeader Map<String, String> headers);
}

package com.taskflow.worker.remote.client;

import java.time.*;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name="taskflow-order-service",contextId="salesReport",url="${taskflow.order.url}",configuration=OrderTimeoutRemote.Timeouts.class)
public interface SalesReportRemote {
    record Generate(String requestKey,long shopId,LocalDate businessDate,String timeZone) { }
    record Report(String id,long shopId,LocalDate businessDate,String timeZone,long orderCount,
                  long totalQuantity,long totalAmountCents,Instant generatedAt) { }
    @PostMapping("/internal/v1/sales-reports/insert")
    Report insert(@RequestBody Generate request,@RequestHeader Map<String,String> headers);
}

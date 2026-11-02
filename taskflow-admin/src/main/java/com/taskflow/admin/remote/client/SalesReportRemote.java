package com.taskflow.admin.remote.client;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.taskflow.admin.sales.config.SalesClientProperties;
import com.taskflow.admin.sales.dto.SalesResponse.*;
import org.springframework.cloud.openfeign.*;
import org.springframework.web.bind.annotation.*;

@FeignClient(name="taskflow-order-service",contextId="salesQuery",url="${taskflow.sales.url}",configuration=SalesReportRemote.Timeouts.class)
public interface SalesReportRemote {
    @GetMapping("/internal/v1/sales-reports/select")
    Page select(@SpringQueryMap Map<String,String> query,@RequestHeader Map<String,String> headers);
    @GetMapping("/internal/v1/sales-reports/selectById")
    Detail detail(@RequestParam String id,@RequestHeader Map<String,String> headers);
    @GetMapping("/internal/v1/sales-reports/selectExport")
    Export export(@RequestParam String id,@RequestHeader Map<String,String> headers);
    class Timeouts {
        @org.springframework.context.annotation.Bean
        public feign.Request.Options options(SalesClientProperties properties) {
            return new feign.Request.Options(properties.connectTimeout().toMillis(),TimeUnit.MILLISECONDS,
                    properties.requestTimeout().toMillis(),TimeUnit.MILLISECONDS,false);
        }
    }
}

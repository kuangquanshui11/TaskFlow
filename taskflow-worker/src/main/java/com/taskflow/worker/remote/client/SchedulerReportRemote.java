package com.taskflow.worker.remote.client;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.cloud.openfeign.*;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="taskflow-scheduler",contextId="schedulerReport",url="${taskflow.remote.report-url:${taskflow.execution.scheduler-url}}",configuration=SchedulerReportRemote.Timeouts.class)
public interface SchedulerReportRemote {
    @PostMapping("/internal/v1/attempts/updateResult")
    String report(@RequestBody Object body,@RequestHeader Map<String,String> headers);
    // 仅由 Feign 子上下文装配，避免某个调用方的超时覆盖所有客户端。
    class Timeouts {
        @org.springframework.context.annotation.Bean
        public feign.Request.Options options(com.taskflow.worker.execution.config.ExecutionProperties properties) {
            return new feign.Request.Options(properties.connectTimeout().toMillis(),TimeUnit.MILLISECONDS,
                    properties.requestTimeout().toMillis(),TimeUnit.MILLISECONDS,false);
        }
    }
}

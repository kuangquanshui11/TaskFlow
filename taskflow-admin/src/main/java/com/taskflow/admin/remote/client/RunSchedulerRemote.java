package com.taskflow.admin.remote.client;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.cloud.openfeign.*;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="taskflow-scheduler",contextId="runScheduler",url="${taskflow.remote.run-url:${taskflow.execution.scheduler-url}}",configuration=RunSchedulerRemote.Timeouts.class)
public interface RunSchedulerRemote {
    @GetMapping("/internal/v1/runs/{action}")
    String get(@PathVariable("action") String action,@SpringQueryMap Map<String,String> query,@RequestHeader Map<String,String> headers);
    @PostMapping("/internal/v1/runs/insert")
    String insert(@RequestBody Object body,@RequestHeader Map<String,String> headers);
    // 仅由 Feign 子上下文装配，避免某个调用方的超时覆盖所有客户端。
    class Timeouts {
        @org.springframework.context.annotation.Bean
        public feign.Request.Options options(com.taskflow.admin.run.config.RunClientProperties properties) {
            return new feign.Request.Options(properties.connectTimeout().toMillis(),TimeUnit.MILLISECONDS,
                    properties.requestTimeout().toMillis(),TimeUnit.MILLISECONDS,false);
        }
    }
}

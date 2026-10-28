package com.taskflow.scheduler.remote.client;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.cloud.openfeign.*;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="taskflow-worker",contextId="workerDispatch",url="${taskflow.remote.worker-url:${taskflow.execution.worker-url}}",configuration=WorkerDispatchRemote.Timeouts.class)
public interface WorkerDispatchRemote {
    @PostMapping("/internal/v1/executions/insert")
    String insert(@RequestBody Object body,@RequestHeader Map<String,String> headers);
    // 仅由 Feign 子上下文装配，避免某个调用方的超时覆盖所有客户端。
    class Timeouts {
        @org.springframework.context.annotation.Bean
        public feign.Request.Options options(com.taskflow.scheduler.execution.config.RunProperties properties) {
            return new feign.Request.Options(properties.connectTimeout().toMillis(),TimeUnit.MILLISECONDS,
                    properties.requestTimeout().toMillis(),TimeUnit.MILLISECONDS,false);
        }
    }
}

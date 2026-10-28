package com.taskflow.admin.remote.client;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.cloud.openfeign.*;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="taskflow-scheduler",contextId="planScheduler",url="${taskflow.remote.plan-url:${taskflow.job-sync.scheduler-url}}",configuration=PlanSchedulerRemote.Timeouts.class)
public interface PlanSchedulerRemote {
    @PostMapping("/internal/v1/plans/update")
    String update(@RequestBody Object body,@RequestHeader Map<String,String> headers);
    @GetMapping("/internal/v1/plans/selectByJobId")
    String select(@RequestParam("id") long id,@RequestHeader Map<String,String> headers);
    // 仅由 Feign 子上下文装配，避免某个调用方的超时覆盖所有客户端。
    class Timeouts {
        @org.springframework.context.annotation.Bean
        public feign.Request.Options options(com.taskflow.admin.jobsync.config.JobSyncProperties properties) {
            return new feign.Request.Options(properties.connectTimeout().toMillis(),TimeUnit.MILLISECONDS,
                    properties.requestTimeout().toMillis(),TimeUnit.MILLISECONDS,false);
        }
    }
}

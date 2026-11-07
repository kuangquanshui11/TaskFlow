package com.taskflow.scheduler.remote.client;

import java.util.Map;
import java.util.UUID;
import com.taskflow.scheduler.execution.dto.AttemptEvents.WorkerEvents;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "taskflow-worker", contextId = "workerAttemptEvents",
        url = "${taskflow.remote.worker-url:${taskflow.execution.worker-url}}")
public interface WorkerAttemptEventsRemote {
    String TARGET_HEADER = "X-Taskflow-Worker-Id";

    @GetMapping("/internal/v1/executions/selectAttemptEvents")
    WorkerEvents select(@RequestParam("runId") UUID runId, @RequestParam("attemptId") UUID attemptId,
            @RequestHeader(TARGET_HEADER) String workerId, @RequestHeader Map<String, String> headers);
}

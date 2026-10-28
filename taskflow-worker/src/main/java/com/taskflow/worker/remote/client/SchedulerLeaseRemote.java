package com.taskflow.worker.remote.client;

import java.util.Map;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "taskflow-scheduler", contextId = "schedulerLease",
        url = "${taskflow.remote.report-url:${taskflow.execution.scheduler-url}}",
        configuration = SchedulerReportRemote.Timeouts.class)
public interface SchedulerLeaseRemote {
    @PostMapping("/internal/v1/attempts/updateLease")
    LeaseAcknowledgement updateLease(@RequestBody LeaseRequest request, @RequestHeader Map<String, String> headers);
}

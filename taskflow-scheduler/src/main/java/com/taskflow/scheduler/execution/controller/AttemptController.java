package com.taskflow.scheduler.execution.controller;

import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.service.IExecutionLeaseService;
import com.taskflow.scheduler.execution.service.IRunService;

@RestController
@RequestMapping("/internal/v1/attempts")
public class AttemptController {
    private final IRunService runs;
    private final IExecutionLeaseService leases;
    public AttemptController(IRunService runs, IExecutionLeaseService leases) { this.runs = runs; this.leases = leases; }
    @PostMapping("/updateLease")
    public LeaseAcknowledgement updateLease(@Valid @RequestBody LeaseRequest request) {
        return leases.updateLease(request);
    }
    @PostMapping("/updateResult")
    public Map<String,Boolean> updateResult(@Valid @RequestBody Result result) {
        runs.updateResult(result); return Map.of("accepted", true);
    }
}

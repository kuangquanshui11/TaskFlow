package com.taskflow.worker.execution.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.taskflow.worker.execution.dto.AttemptEvents;
import com.taskflow.worker.execution.dto.AttemptLogs;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.service.IAttemptEventService;
import com.taskflow.worker.execution.service.IAttemptLogService;
import com.taskflow.worker.execution.service.IExecutionService;

@RestController
@RequestMapping("/internal/v1/executions")
public class ExecutionController {
    private final IExecutionService executions;
    private final IAttemptEventService events;
    private final IAttemptLogService logs;
    public ExecutionController(IExecutionService executions, IAttemptEventService events, IAttemptLogService logs) {
        this.executions = executions;
        this.events = events;
        this.logs = logs;
    }
    @PostMapping("/insert") @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted insert(@Valid @RequestBody Dispatch request) { return executions.insert(request); }
    @GetMapping("/selectById")
    public Accepted selectById(@RequestParam UUID attemptId) { return executions.selectById(attemptId); }
    @GetMapping("/selectAttemptEvents")
    public AttemptEvents selectAttemptEvents(@RequestParam UUID runId, @RequestParam UUID attemptId,
            @RequestHeader("X-Taskflow-Worker-Id") String workerId) {
        return events.select(runId, attemptId, workerId);
    }
    @GetMapping("/selectAttemptLogs")
    public AttemptLogs selectAttemptLogs(@RequestParam UUID runId, @RequestParam UUID attemptId,
            @RequestHeader("X-Taskflow-Worker-Id") String workerId, @RequestParam(defaultValue = "0") int offset) {
        return logs.select(runId, attemptId, workerId, offset);
    }
}

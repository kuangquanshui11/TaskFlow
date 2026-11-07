package com.taskflow.scheduler.execution.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.Create;
import com.taskflow.scheduler.execution.model.RunRecords.*;
import com.taskflow.scheduler.execution.dto.AttemptEvents;
import com.taskflow.scheduler.execution.service.IAttemptEventService;
import com.taskflow.scheduler.execution.service.IRunService;

@RestController
@RequestMapping("/internal/v1/runs")
public class RunController {
    private final IRunService runs;
    private final IAttemptEventService events;
    public RunController(IRunService runs, IAttemptEventService events) {
        this.runs = runs;
        this.events = events;
    }
    @PostMapping("/insert") @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView insert(@Valid @RequestBody Create request) { return runs.insert(request); }
    @GetMapping("/selectById")
    public RunView selectById(@RequestParam UUID id) { return runs.selectById(id); }
    @GetMapping("/selectAttemptEvents")
    public AttemptEvents.Timeline selectAttemptEvents(@RequestParam UUID runId, @RequestParam UUID attemptId) {
        return events.select(runId, attemptId);
    }
    @GetMapping("/selectGlobal")
    public Page selectGlobal(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Long jobId, @RequestParam(required = false) String state,
            @RequestParam(required = false) String triggerType, @RequestParam(required = false) UUID runId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return runs.selectGlobal(new com.taskflow.scheduler.execution.dto.RunQuery(from, to, jobId, state, triggerType, runId, page, pageSize));
    }
    @GetMapping("/selectByRequestKey")
    public RunView selectByRequestKey(@RequestParam UUID requestKey) { return runs.selectByRequestKey(requestKey); }
    @GetMapping("/selectSummary")
    public Overview selectSummary(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return runs.selectSummary(parseWindowTime(from), parseWindowTime(to));
    }
    private java.time.Instant parseWindowTime(String value) {
        if (value == null) return null;
        try {
            if (!value.endsWith("Z")) throw new IllegalArgumentException();
            return java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException | IllegalArgumentException invalid) {
            throw new com.taskflow.scheduler.execution.exception.RunException(400, "INVALID_ARGUMENT", "时间必须为以 Z 结尾的 UTC ISO-8601");
        }
    }
    @GetMapping("/select")
    public Page select(@RequestParam long jobId, @RequestParam(defaultValue="1") int page,
                       @RequestParam(defaultValue="10") int pageSize) { return runs.select(jobId, page, pageSize); }
}

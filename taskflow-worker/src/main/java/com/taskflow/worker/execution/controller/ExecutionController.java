package com.taskflow.worker.execution.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.service.IExecutionService;

@RestController
@RequestMapping("/internal/v1/executions")
public class ExecutionController {
    private final IExecutionService executions;
    public ExecutionController(IExecutionService executions) { this.executions = executions; }
    @PostMapping("/insert") @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted insert(@Valid @RequestBody Dispatch request) { return executions.insert(request); }
    @GetMapping("/selectById")
    public Accepted selectById(@RequestParam UUID attemptId) { return executions.selectById(attemptId); }
}

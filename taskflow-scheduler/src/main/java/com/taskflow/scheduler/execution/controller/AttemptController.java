package com.taskflow.scheduler.execution.controller;

import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.Result;
import com.taskflow.scheduler.execution.service.IRunService;

@RestController
@RequestMapping("/internal/v1/attempts")
public class AttemptController {
    private final IRunService runs;
    public AttemptController(IRunService runs) { this.runs = runs; }
    @PostMapping("/updateResult")
    public Map<String,Boolean> updateResult(@Valid @RequestBody Result result) {
        runs.updateResult(result); return Map.of("accepted", true);
    }
}

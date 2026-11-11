package com.taskflow.scheduler.dispatch.controller;

import java.util.UUID;
import com.taskflow.scheduler.dispatch.dto.DispatchFailures.*;
import com.taskflow.scheduler.dispatch.service.IDispatchFailureService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/runs")
public class DispatchFailureController {
    private final IDispatchFailureService failures;

    public DispatchFailureController(IDispatchFailureService failures) { this.failures = failures; }

    @GetMapping("/selectDispatchFailures")
    public View select(@RequestParam(required = false) UUID runId, @RequestParam(required = false) UUID attemptId,
                       @RequestParam(required = false) UUID eventId) {
        return failures.select(runId, attemptId, eventId);
    }

    @PostMapping("/updateDispatchFailure")
    public Disposition update(@Valid @RequestBody DispositionRequest request) { return failures.update(request); }
}

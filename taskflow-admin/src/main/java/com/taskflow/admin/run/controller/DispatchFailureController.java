package com.taskflow.admin.run.controller;

import java.util.UUID;
import com.taskflow.admin.run.dto.DispatchFailureRequests.Disposition;
import com.taskflow.admin.run.service.IDispatchFailureService;
import com.taskflow.admin.system.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/runs")
public class DispatchFailureController {
    private final IDispatchFailureService failures;

    public DispatchFailureController(IDispatchFailureService failures) { this.failures = failures; }

    @GetMapping("/selectDispatchFailures")
    public ApiResponse<JsonNode> select(@RequestParam(required = false) UUID runId, @RequestParam(required = false) UUID attemptId,
                                       @RequestParam(required = false) UUID eventId) {
        return ApiResponse.success(failures.select(runId, attemptId, eventId));
    }

    @PostMapping("/updateDispatchFailure")
    public ApiResponse<JsonNode> update(@Valid @RequestBody Disposition request) {
        return ApiResponse.success(failures.update(request));
    }
}

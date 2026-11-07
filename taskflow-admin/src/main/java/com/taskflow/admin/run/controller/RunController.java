package com.taskflow.admin.run.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.taskflow.admin.run.dto.RunRequests.Insert;
import com.taskflow.admin.run.service.IRunService;
import com.taskflow.admin.system.dto.ApiResponse;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {
    private final IRunService runs;
    public RunController(IRunService runs) { this.runs = runs; }
    @PostMapping("/insert") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<JsonNode> insert(@Valid @RequestBody Insert request) { return ApiResponse.success(runs.insert(request)); }
    @GetMapping("/select")
    public ApiResponse<JsonNode> select(@RequestParam long jobId, @RequestParam(defaultValue="1") int page,
                                       @RequestParam(defaultValue="10") int pageSize) {
        return ApiResponse.success(runs.select(jobId, page, pageSize));
    }
    @GetMapping("/selectById")
    public ApiResponse<JsonNode> selectById(@RequestParam UUID id) { return ApiResponse.success(runs.selectById(id)); }
    @GetMapping("/selectAttemptEvents")
    public ApiResponse<JsonNode> selectAttemptEvents(@RequestParam UUID runId, @RequestParam UUID attemptId) {
        return ApiResponse.success(runs.selectAttemptEvents(runId, attemptId));
    }
    @GetMapping("/selectGlobal")
    public ApiResponse<JsonNode> selectGlobal(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Long jobId, @RequestParam(required = false) String state,
            @RequestParam(required = false) String triggerType, @RequestParam(required = false) UUID runId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(runs.selectGlobal(new com.taskflow.admin.run.dto.RunQuery(from, to, jobId, state, triggerType, runId, page, pageSize)));
    }
    @GetMapping("/selectSummary")
    public ApiResponse<JsonNode> selectSummary(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return ApiResponse.success(runs.selectSummary(from, to));
    }
    @GetMapping("/selectByRequestKey")
    public ApiResponse<JsonNode> selectByRequestKey(@RequestParam UUID requestKey) {
        return ApiResponse.success(runs.selectByRequestKey(requestKey));
    }
}

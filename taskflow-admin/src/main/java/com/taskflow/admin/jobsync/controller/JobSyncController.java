package com.taskflow.admin.jobsync.controller;

import com.taskflow.admin.jobsync.model.JobSyncRecords.Status;
import com.taskflow.admin.jobsync.model.PlanView;
import com.taskflow.admin.jobsync.service.IJobSyncService;
import com.taskflow.admin.system.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/job-sync")
public class JobSyncController {
    private final IJobSyncService sync;
    public JobSyncController(IJobSyncService sync) { this.sync = sync; }

    @GetMapping("/selectByJobId")
    public ApiResponse<Status> selectByJobId(@RequestParam @Positive long id) {
        return ApiResponse.success(sync.selectByJobId(id));
    }

    @PostMapping("/updateRetry")
    public ApiResponse<Status> retry(@Valid @RequestBody Retry request) {
        return ApiResponse.success(sync.retry(request.id(), request.configVersion()));
    }

    @GetMapping("/selectPlanByJobId")
    public ApiResponse<PlanView> selectPlanByJobId(@RequestParam @Positive long id) {
        return ApiResponse.success(sync.selectPlanByJobId(id));
    }

    public record Retry(@Positive long id, @Positive long configVersion) { }
}

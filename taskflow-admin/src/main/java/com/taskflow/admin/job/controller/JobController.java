package com.taskflow.admin.job.controller;

import com.taskflow.admin.job.dto.JobRequests;
import com.taskflow.admin.job.dto.JobResponse;
import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.system.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
    private final IJobService jobs;
    private final JsonMapper json;

    public JobController(IJobService jobs, JsonMapper json) {
        this.jobs = jobs;
        this.json = json;
    }

    @PostMapping("/insert")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JobResponse> insert(@Valid @RequestBody JobRequests.Write request) {
        return ApiResponse.success(JobResponse.from(jobs.insert(request.toConfiguration()), json));
    }

    @PostMapping("/update")
    public ApiResponse<JobResponse> update(@Valid @RequestBody JobRequests.Update request) {
        return ApiResponse.success(JobResponse.from(jobs.update(
                request.id(), request.configVersion(), request.job().toConfiguration()), json));
    }

    @PostMapping("/enable")
    public ApiResponse<JobResponse> enable(@Valid @RequestBody JobRequests.Mutation request) {
        return ApiResponse.success(JobResponse.from(
                jobs.changeEnabled(request.id(), request.configVersion(), true), json));
    }

    @PostMapping("/disable")
    public ApiResponse<JobResponse> disable(@Valid @RequestBody JobRequests.Mutation request) {
        return ApiResponse.success(JobResponse.from(
                jobs.changeEnabled(request.id(), request.configVersion(), false), json));
    }

    @PostMapping("/delete")
    public ApiResponse<Deleted> delete(@Valid @RequestBody JobRequests.Mutation request) {
        jobs.delete(request.id(), request.configVersion());
        return ApiResponse.success(new Deleted(request.id(), request.configVersion() + 1));
    }

    public record Deleted(long id, long configVersion) {
    }
}

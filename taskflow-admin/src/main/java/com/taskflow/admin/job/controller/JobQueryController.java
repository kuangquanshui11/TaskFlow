package com.taskflow.admin.job.controller;

import java.util.List;

import com.taskflow.admin.job.dto.JobRequests;
import com.taskflow.admin.job.dto.JobResponse;
import com.taskflow.admin.job.model.JobFilter;
import com.taskflow.admin.job.model.JobSummary;
import com.taskflow.admin.job.service.IJobDetailService;
import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.system.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobQueryController {
    private final IJobService jobs;
    private final IJobDetailService details;
    private final JsonMapper json;

    public JobQueryController(IJobService jobs, IJobDetailService details, JsonMapper json) {
        this.jobs = jobs;
        this.details = details;
        this.json = json;
    }

    @GetMapping("/select")
    public ApiResponse<Page> select(@Valid @ModelAttribute JobRequests.Select request) {
        var result = jobs.select(new JobFilter(request.keyword(), request.enabled(),
                request.handlerKey(), request.page(), request.pageSize()));
        return ApiResponse.success(new Page(result.records().stream()
                .map(job -> JobResponse.from(job, json)).toList(),
                result.total(), result.page(), result.pageSize()));
    }

    @GetMapping("/selectById")
    public ApiResponse<JobResponse> selectById(@RequestParam @Positive long id) {
        return ApiResponse.success(JobResponse.from(details.selectById(id), json));
    }

    @GetMapping("/selectSummary")
    public ApiResponse<JobSummary> selectSummary() {
        return ApiResponse.success(jobs.selectSummary());
    }

    public record Page(List<JobResponse> records, long total, int page, int pageSize) {
    }
}

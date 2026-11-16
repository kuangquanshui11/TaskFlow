package com.taskflow.admin.job.controller;

import java.util.function.Supplier;
import com.taskflow.admin.audit.model.OperationAudit.Action;
import com.taskflow.admin.audit.service.IOperationAuditService;
import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.jobsync.exception.PlanQueryException;
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
    private final IOperationAuditService audit;

    public JobController(IJobService jobs, JsonMapper json, IOperationAuditService audit) {
        this.jobs = jobs;
        this.json = json;
        this.audit = audit;
    }

    @PostMapping("/insert")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JobResponse> insert(@Valid @RequestBody JobRequests.Write request) {
        return write(Action.JOB_INSERT, null, null,
                () -> JobResponse.from(jobs.insert(request.toConfiguration()), json));
    }

    @PostMapping("/update")
    public ApiResponse<JobResponse> update(@Valid @RequestBody JobRequests.Update request) {
        return write(Action.JOB_UPDATE, request.id(), request.configVersion(), () -> JobResponse.from(jobs.update(
                request.id(), request.configVersion(), request.job().toConfiguration()), json));
    }

    @PostMapping("/enable")
    public ApiResponse<JobResponse> enable(@Valid @RequestBody JobRequests.Mutation request) {
        return write(Action.JOB_ENABLE, request.id(), request.configVersion(), () -> JobResponse.from(
                jobs.changeEnabled(request.id(), request.configVersion(), true), json));
    }

    @PostMapping("/disable")
    public ApiResponse<JobResponse> disable(@Valid @RequestBody JobRequests.Mutation request) {
        return write(Action.JOB_DISABLE, request.id(), request.configVersion(), () -> JobResponse.from(
                jobs.changeEnabled(request.id(), request.configVersion(), false), json));
    }

    @PostMapping("/delete")
    public ApiResponse<Deleted> delete(@Valid @RequestBody JobRequests.Mutation request) {
        return write(Action.JOB_DELETE, request.id(), request.configVersion(), () -> {
            jobs.delete(request.id(), request.configVersion());
            return new Deleted(request.id(), request.configVersion() + 1);
        });
    }

    private <T> ApiResponse<T> write(Action action, Long jobId, Long version, Supplier<T> operation) {
        try {
            return ApiResponse.success(operation.get());
        } catch (JobException rejected) {
            audit.jobRejected(action, jobId, version, rejected.code());
            throw rejected;
        } catch (PlanQueryException rejected) {
            audit.jobRejected(action, jobId, version, rejected.code());
            throw rejected;
        }
    }

    public record Deleted(long id, long configVersion) {
    }
}

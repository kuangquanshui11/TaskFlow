package com.taskflow.admin.job.controller;

import java.time.Instant;
import java.util.List;

import com.taskflow.admin.job.dto.JobRequests;
import com.taskflow.admin.job.service.ISchedulePreviewService;
import com.taskflow.admin.system.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
public class SchedulePreviewController {
    private final ISchedulePreviewService schedules;

    public SchedulePreviewController(ISchedulePreviewService schedules) {
        this.schedules = schedules;
    }

    @PostMapping("/preview")
    public ApiResponse<Preview> preview(@Valid @RequestBody JobRequests.Preview request) {
        Instant from = request.from() == null ? Instant.now() : request.from();
        return ApiResponse.success(new Preview(request.timeZone(), schedules.preview(
                request.cronExpression(), request.timeZone(), from, request.count())));
    }

    public record Preview(String timeZone, List<Instant> nextFireTimes) {
    }
}

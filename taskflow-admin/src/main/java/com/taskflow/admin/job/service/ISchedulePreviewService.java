package com.taskflow.admin.job.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.springframework.scheduling.support.CronExpression;

public interface ISchedulePreviewService {
    ZoneId validateTimeZone(String timeZone);
    CronExpression validateCron(String expression);
    List<Instant> preview(String expression, String timeZone, Instant from, int count);
}

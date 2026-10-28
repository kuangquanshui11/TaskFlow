package com.taskflow.admin.job.model;

public record JobSummary(long total, long enabled, long disabled, long manual, long cron) {
}

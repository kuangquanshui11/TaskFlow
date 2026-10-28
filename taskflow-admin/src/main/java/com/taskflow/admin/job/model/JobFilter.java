package com.taskflow.admin.job.model;

public record JobFilter(String keyword, Boolean enabled, String handlerKey, int page, int pageSize) {
}

package com.taskflow.admin.run.dto;

import java.util.UUID;

public record RunQuery(String from, String to, Long jobId, String state, String triggerType,
        UUID runId, String resultCode, int page, int pageSize) { }

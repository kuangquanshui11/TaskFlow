package com.taskflow.admin.run.service;

import java.util.UUID;
import com.taskflow.admin.run.dto.RunRequests.Insert;
import com.taskflow.admin.run.dto.RunRequests.Rerun;
import com.taskflow.admin.run.dto.RunRequests.RerunConfiguration;
import tools.jackson.databind.JsonNode;

public interface IRunService {
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('run:trigger')")
    JsonNode insert(Insert request);
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('run:trigger')")
    JsonNode insertRerun(Rerun request);
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('run:trigger')")
    RerunConfiguration selectRerunConfiguration(UUID sourceRunId);
    JsonNode select(long jobId, int page, int pageSize);
    JsonNode selectGlobal(com.taskflow.admin.run.dto.RunQuery query);
    JsonNode selectSummary(String from, String to);
    JsonNode selectById(UUID id);
    JsonNode selectAttemptEvents(UUID runId, UUID attemptId);
    JsonNode selectByRequestKey(UUID requestKey);
}

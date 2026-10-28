package com.taskflow.admin.run.service;

import java.util.UUID;
import com.taskflow.admin.run.dto.RunRequests.Insert;
import tools.jackson.databind.JsonNode;

public interface IRunService {
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('run:trigger')")
    JsonNode insert(Insert request);
    JsonNode select(long jobId, int page, int pageSize);
    JsonNode selectById(UUID id);
    JsonNode selectByRequestKey(UUID requestKey);
}

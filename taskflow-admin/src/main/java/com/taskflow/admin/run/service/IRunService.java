package com.taskflow.admin.run.service;

import java.util.UUID;
import com.taskflow.admin.run.dto.RunRequests.Insert;
import tools.jackson.databind.JsonNode;

public interface IRunService {
    JsonNode insert(Insert request);
    JsonNode select(long jobId, int page, int pageSize);
    JsonNode selectById(UUID id);
    JsonNode selectByRequestKey(UUID requestKey);
}

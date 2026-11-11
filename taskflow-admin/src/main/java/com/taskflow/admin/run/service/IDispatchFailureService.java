package com.taskflow.admin.run.service;

import java.util.UUID;
import com.taskflow.admin.run.dto.DispatchFailureRequests.Disposition;
import org.springframework.security.access.prepost.PreAuthorize;
import tools.jackson.databind.JsonNode;

public interface IDispatchFailureService {
    @PreAuthorize("hasAuthority('run:read')")
    JsonNode select(UUID runId, UUID attemptId, UUID eventId);
    @PreAuthorize("hasAuthority('dispatch:resolve')")
    JsonNode update(Disposition request);
}

package com.taskflow.admin.run.service.impl;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import com.taskflow.admin.run.client.SchedulerClient;
import com.taskflow.admin.run.dto.DispatchFailureRequests.Disposition;
import com.taskflow.admin.run.service.IDispatchFailureService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class DispatchFailureServiceImpl implements IDispatchFailureService {
    private final SchedulerClient scheduler;

    public DispatchFailureServiceImpl(SchedulerClient scheduler) { this.scheduler = scheduler; }

    @Override
    public JsonNode select(UUID runId, UUID attemptId, UUID eventId) {
        var query = new ArrayList<String>();
        if (runId != null) query.add("runId=" + runId);
        if (attemptId != null) query.add("attemptId=" + attemptId);
        if (eventId != null) query.add("eventId=" + eventId);
        return scheduler.get("/internal/v1/runs/selectDispatchFailures" + (query.isEmpty() ? "" : "?" + String.join("&", query)));
    }

    @Override
    public JsonNode update(Disposition request) {
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        return scheduler.post("/internal/v1/runs/updateDispatchFailure", Map.of(
                "requestKey", request.requestKey(), "source", request.source(), "id", request.id(), "action", request.action(),
                "actor", actor, "conclusion", request.conclusion(), "evidence", request.evidence()));
    }
}

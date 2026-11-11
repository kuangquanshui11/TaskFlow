package com.taskflow.scheduler.dispatch.service;

import java.util.UUID;
import com.taskflow.scheduler.dispatch.dto.DispatchFailures.*;

public interface IDispatchFailureService {
    View select(UUID runId, UUID attemptId, UUID eventId);
    Disposition update(DispositionRequest request);
}

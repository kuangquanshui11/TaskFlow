package com.taskflow.scheduler.execution.client;

import java.util.Optional;
import java.util.UUID;
import com.taskflow.scheduler.execution.config.RunProperties;
import com.taskflow.scheduler.execution.dto.AttemptEvents.Batch;
import com.taskflow.scheduler.execution.dto.AttemptEvents.WorkerEvent;
import com.taskflow.scheduler.remote.client.WorkerAttemptEventsRemote;
import com.taskflow.scheduler.remote.model.RemoteErrors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class WorkerAttemptEventClient {
    private final RunProperties properties;
    private final WorkerAttemptEventsRemote remote;

    public WorkerAttemptEventClient(RunProperties properties, WorkerAttemptEventsRemote remote) {
        this.properties = properties;
        this.remote = remote;
    }

    public Optional<Batch<WorkerEvent>> select(UUID runId, UUID attemptId, String workerId, int limit) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Worker event query inside SQL transaction");
        if (properties.dispatchToken().isBlank()) return Optional.empty();
        try {
            var response = remote.select(runId, attemptId, workerId,
                    RemoteErrors.headers(properties.dispatchToken(), runId.toString()));
            if (response == null || !runId.equals(response.runId()) || !attemptId.equals(response.attemptId())
                    || !workerId.equals(response.workerId()) || response.truncated() == null
                    || response.events() == null || response.events().size() > limit
                    || response.events().stream().anyMatch(event -> event == null
                        || event.id() <= 0 || event.eventType() == null || event.occurredAt() == null)) return Optional.empty();
            return Optional.of(new Batch<>(response.events(), response.truncated()));
        } catch (feign.FeignException | org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException unavailable) {
            return Optional.empty();
        }
    }
}

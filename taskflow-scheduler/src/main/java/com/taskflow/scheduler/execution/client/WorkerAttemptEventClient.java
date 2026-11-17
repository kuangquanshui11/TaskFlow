package com.taskflow.scheduler.execution.client;

import java.util.Optional;
import java.util.UUID;
import com.taskflow.scheduler.execution.config.RunProperties;
import com.taskflow.scheduler.execution.dto.AttemptEvents.Batch;
import com.taskflow.scheduler.execution.dto.AttemptEvents.WorkerEvent;
import com.taskflow.scheduler.execution.dto.AttemptEvents.WorkerEvents;
import com.taskflow.scheduler.execution.dto.AttemptLogs.WorkerPage;
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
        return snapshot(runId, attemptId, workerId, limit).map(response -> new Batch<>(response.events(), response.truncated()));
    }

    public Optional<WorkerPage> selectLogs(UUID runId, UUID attemptId, String workerId, int offset) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Worker log query inside SQL transaction");
        if (properties.dispatchToken().isBlank()) return Optional.empty();
        try {
            var response = remote.selectLogs(runId, attemptId, offset, workerId,
                    RemoteErrors.headers(properties.dispatchToken(), runId.toString()));
            if (response == null || !runId.equals(response.runId()) || !attemptId.equals(response.attemptId())
                    || !workerId.equals(response.workerId()) || response.entries() == null || response.entries().size() > 100
                    || response.nextOffset() != null && (response.entries().size() != 100 || response.nextOffset() != offset + 100L)
                    || response.entries().stream().anyMatch(entry -> entry == null || !runId.equals(entry.runId())
                            || !attemptId.equals(entry.attemptId()) || entry.occurredAt() == null
                            || !("INFO".equals(entry.level()) || "WARN".equals(entry.level()))
                            || entry.message() == null || entry.message().length() > 1000))
                return Optional.empty();
            return Optional.of(response);
        } catch (feign.FeignException | org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException unavailable) {
            return Optional.empty();
        }
    }

    public Optional<WorkerEvents> snapshot(UUID runId, UUID attemptId, String workerId, int limit) {
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
            var failure = response.reportFailure();
            if (failure != null && (failure.observedAt() == null || failure.attempts() < 1
                    || failure.code() == null || !failure.code().matches("REPORT_[A-Z_]{1,56}"))) return Optional.empty();
            return Optional.of(response);
        } catch (feign.FeignException | org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException unavailable) {
            return Optional.empty();
        }
    }
}

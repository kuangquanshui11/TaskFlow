package com.taskflow.scheduler.dispatch.service.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.taskflow.scheduler.dispatch.dto.DispatchFailures.*;
import com.taskflow.scheduler.dispatch.mapper.DispatchFailureMapper;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.dispatch.service.IDispatchFailureService;
import com.taskflow.scheduler.execution.client.WorkerAttemptEventClient;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.model.RunRecords.Attempt;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DispatchFailureServiceImpl implements IDispatchFailureService {
    private static final int LIMIT = 25;
    private final DispatchFailureMapper failures;
    private final DispatchOutboxMapper outbox;
    private final RunDataMapper runs;
    private final WorkerAttemptEventClient workers;
    private final TransactionTemplate transactions;

    public DispatchFailureServiceImpl(DispatchFailureMapper failures, DispatchOutboxMapper outbox, RunDataMapper runs,
                                      WorkerAttemptEventClient workers, PlatformTransactionManager manager) {
        this.failures = failures;
        this.outbox = outbox;
        this.runs = runs;
        this.workers = workers;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public View select(UUID runId, UUID attemptId, UUID eventId) {
        if (attemptId != null && eventId != null && !attemptId.equals(eventId))
            throw new RunException(400, "INVALID_ARGUMENT", "Attempt 与派发事件标识不一致");
        var id = attemptId == null ? eventId : attemptId;
        Attempt attempt = id == null
                ? (runId == null ? null : runs.attempts(runId).stream().reduce((first, last) -> last).orElse(null))
                : runs.attempt(id).orElse(null);
        if ((runId != null && (attempt == null || !runId.equals(attempt.runId()))) || (attemptId != null && attempt == null))
            throw new RunException(404, "ATTEMPT_NOT_FOUND", "未找到匹配的 Attempt");
        var dead = failures.recent(attempt == null ? null : attempt.id(), eventId, LIMIT + 1);
        var records = new ArrayList<Failure>();
        for (var entry : dead.stream().limit(LIMIT).toList()) records.add(deadLetter(entry));
        if (attempt == null) return new View(null, null, eventId, null, null, null, "NOT_ASSIGNED", dead.size() > LIMIT, records);

        var view = appendAttemptFailures(attempt, records);
        var leaseUntil = attempt.leaseUntil() == null ? attempt.expiresAt() : attempt.leaseUntil();
        return new View(attempt.runId(), attempt.id(), attempt.id(), attempt.state(), attempt.leaseVersion(),
                leaseUntil, view, dead.size() > LIMIT, records);
    }

    private String appendAttemptFailures(Attempt attempt, List<Failure> records) {
        var id = attempt.id();
        outbox.find(id, false).filter(entry -> "FAILED".equals(entry.state())).ifPresent(entry ->
                records.add(new Failure(Source.OUTBOX, id.toString(), attempt.runId(), id, entry.eventId(),
                        entry.lastErrorCode(), entry.publishAttempts(), entry.target() == null ? null : entry.target().queue(),
                        null, null, failures.history(Source.OUTBOX, id.toString()))));
        var worker = attempt.targetWorkerId() == null ? attempt.ownerWorkerId() : attempt.targetWorkerId();
        if (worker == null) return "NOT_ASSIGNED";
        var snapshot = workers.snapshot(attempt.runId(), id, worker, 100);
        var history = failures.history(Source.RESULT_REPORT, id.toString());
        var diagnostic = snapshot.map(response -> response.reportFailure()).orElse(null);
        if (diagnostic != null || !history.isEmpty()) {
            var code = diagnostic != null ? diagnostic.code()
                    : snapshot.isEmpty() ? "REPORT_SOURCE_UNAVAILABLE" : "REPORT_DIAGNOSTIC_UNAVAILABLE";
            records.add(new Failure(Source.RESULT_REPORT, id.toString(), attempt.runId(), id, null, code,
                    diagnostic == null ? null : diagnostic.attempts(), null, null,
                    diagnostic == null ? null : diagnostic.observedAt(), history));
        }
        return snapshot.isPresent() ? "AVAILABLE" : "UNAVAILABLE";
    }

    private Failure deadLetter(DeadLetter entry) {
        return new Failure(Source.DEAD_LETTER, entry.id(), entry.runId(), entry.attemptId(), entry.eventId(),
                entry.reason(), null, entry.queue(), entry.sourceQueue(), entry.receivedAt(),
                failures.history(Source.DEAD_LETTER, entry.id()));
    }

    @Override
    public Disposition update(DispositionRequest request) {
        var existing = failures.disposition(request.requestKey());
        if (existing.isPresent()) return replay(request, existing.get());
        var target = target(request);
        try {
            return transactions.execute(status -> {
                var prior = failures.disposition(request.requestKey());
                if (prior.isPresent()) return replay(request, prior.get());
                if (request.action() == Action.RESOLVE) {
                    if (target.attemptId() != null && !runs.lockAttempt(target.attemptId()).filter(Attempt::terminal).isPresent())
                        throw conflict("ATTEMPT_ACTIVE", "结案前需确认 Attempt 已进入终态");
                    if (failures.history(request.source(), request.id()).stream().noneMatch(item -> item.action() == Action.ACKNOWLEDGE))
                        throw conflict("DISPOSITION_NOT_ACKNOWLEDGED", "请先确认收到故障");
                }
                var entry = new Disposition(request.requestKey(), request.source(), request.id(), request.action(), request.actor(),
                        request.conclusion(), request.evidence(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
                failures.record(entry);
                return entry;
            });
        } catch (DuplicateKeyException concurrent) {
            return failures.disposition(request.requestKey()).map(entry -> replay(request, entry))
                    .orElseThrow(() -> conflict("DISPOSITION_EXISTS", "该处置动作已经记录"));
        }
    }

    private Failure target(DispositionRequest request) {
        if (request.source() == Source.DEAD_LETTER) return failures.find(request.id()).map(this::deadLetter)
                .orElseThrow(DispatchFailureServiceImpl::missing);
        UUID attemptId;
        try {
            attemptId = UUID.fromString(request.id());
            if (!attemptId.toString().equals(request.id())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw missing();
        }
        if (request.source() == Source.OUTBOX) {
            var attempt = runs.attempt(attemptId).orElseThrow(DispatchFailureServiceImpl::missing);
            var event = outbox.find(attemptId, false).filter(entry -> "FAILED".equals(entry.state()))
                    .orElseThrow(DispatchFailureServiceImpl::missing);
            return new Failure(Source.OUTBOX, request.id(), attempt.runId(), attemptId, event.eventId(),
                    event.lastErrorCode(), event.publishAttempts(), null, null, null, List.of());
        }
        return select(null, attemptId, null).records().stream()
                .filter(record -> record.source() == request.source() && record.id().equals(request.id()))
                .findFirst().orElseThrow(DispatchFailureServiceImpl::missing);
    }

    private static Disposition replay(DispositionRequest request, Disposition entry) {
        if (request.source() != entry.source() || !request.id().equals(entry.id()) || request.action() != entry.action()
                || !Objects.equals(request.actor(), entry.actor()) || !request.conclusion().equals(entry.conclusion())
                || !request.evidence().equals(entry.evidence()))
            throw conflict("IDEMPOTENCY_CONFLICT", "处置请求键已绑定其他内容");
        return entry;
    }

    private static RunException missing() { return new RunException(404, "DISPATCH_FAILURE_NOT_FOUND", "未找到该派发故障"); }
    private static RunException conflict(String code, String message) { return new RunException(409, code, message); }
}

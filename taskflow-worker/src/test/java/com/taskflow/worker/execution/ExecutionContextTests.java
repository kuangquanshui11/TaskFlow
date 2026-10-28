package com.taskflow.worker.execution;

import java.time.*;
import java.util.UUID;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.exception.ExecutionCancelledException;
import com.taskflow.worker.execution.model.ExecutionContext;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionContextTests {
    private final Instant serverTime = Instant.parse("2026-10-28T00:00:00Z");
    private final Dispatch dispatch = new Dispatch(UUID.randomUUID(), UUID.randomUUID(), 1, 1, serverTime.plusSeconds(60),
            new Snapshot(1, 1, "context", "demoSuccess", null, 50, 3, 1, "MANUAL", null, "UTC", "SKIP", false, null));
    private final LeaseRequest owner = new LeaseRequest(dispatch.attemptId(), dispatch.runId(), 1, "worker-1", UUID.randomUUID());

    @Test void serverClockOffsetDoesNotChangeConservativeLocalBudget() {
        var context = context(ack(serverTime, serverTime.plusSeconds(20)), System.nanoTime());
        assertThatCode(context::checkActive).doesNotThrowAnyException();
        assertThat(context.executionDeadline()).isEqualTo(serverTime.plusSeconds(50));
    }
    @Test void elapsedRequestTimeIsDeductedAndLateInitialAcknowledgementCannotStartBusiness() {
        assertThatThrownBy(() -> context(ack(serverTime, serverTime.plusSeconds(20)), System.nanoTime() - Duration.ofSeconds(21).toNanos()))
                .isInstanceOfSatisfying(ExecutionCancelledException.class, error -> assertThat(error.code()).isEqualTo("LEASE_LOST"));
    }
    @Test void lateRenewalCannotReviveCancelledContext() {
        var context = context(ack(serverTime, serverTime.plusSeconds(20)), System.nanoTime());
        context.cancel("LEASE_LOST");
        context.renewed(ack(serverTime.plusSeconds(10), serverTime.plusSeconds(40)), System.nanoTime());
        assertThatThrownBy(context::checkActive).isInstanceOf(ExecutionCancelledException.class);
        assertThat(context.cancellationCode()).isEqualTo("LEASE_LOST");
    }
    @Test void businessKeySurvivesAttemptChangeAndAttemptAndFencingRemainSeparate() {
        var first = context(ack(serverTime, serverTime.plusSeconds(20)), System.nanoTime());
        var retry = new Dispatch(UUID.randomUUID(), dispatch.runId(), 2, 2, dispatch.expiresAt(), dispatch.snapshot());
        var retryOwner = new LeaseRequest(retry.attemptId(), retry.runId(), 2, owner.workerId(), owner.sessionId());
        var retryAck = new LeaseAcknowledgement(retry.attemptId(), retry.runId(), 2, owner.workerId(), owner.sessionId(),
                serverTime, serverTime.plusSeconds(20), serverTime.plusSeconds(50), dispatch.expiresAt());
        var second = new ExecutionContext(retry, retryOwner, retryAck, Duration.ofSeconds(1), System.nanoTime());
        assertThat(first.businessIdempotencyKey("send")).isEqualTo(second.businessIdempotencyKey("send"));
        assertThat(first.businessIdempotencyKey("send")).isNotEqualTo(first.businessIdempotencyKey("charge"));
        assertThat(first.attemptId()).isNotEqualTo(second.attemptId());
        assertThat(second.leaseVersion()).isEqualTo(2);
        assertThatThrownBy(() -> first.businessIdempotencyKey(" ")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void acknowledgementCannotExtendHardDeadlineOrChangeOwner() {
        var invalid = new LeaseAcknowledgement(dispatch.attemptId(), dispatch.runId(), 1, "other-worker", owner.sessionId(),
                serverTime, serverTime.plusSeconds(20), serverTime.plusSeconds(50), dispatch.expiresAt());
        assertThatThrownBy(() -> context(invalid, System.nanoTime())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context(ack(serverTime, serverTime.plusSeconds(61)), System.nanoTime())).isInstanceOf(IllegalArgumentException.class);
    }
    private ExecutionContext context(LeaseAcknowledgement ack, long started) { return new ExecutionContext(dispatch, owner, ack, Duration.ofSeconds(1), started); }
    private LeaseAcknowledgement ack(Instant now, Instant until) { return new LeaseAcknowledgement(dispatch.attemptId(), dispatch.runId(), 1,
            owner.workerId(), owner.sessionId(), now, until, serverTime.plusSeconds(50), dispatch.expiresAt()); }
}

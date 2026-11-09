package com.taskflow.worker.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.taskflow.worker.cleanup.service.impl.ExpiredDataCleanupHandlerServiceImpl;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.exception.HandlerFailureException;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.order.config.OrderHandlerProperties;
import com.taskflow.worker.remote.client.ExpiredDataCleanupRemote;
import com.taskflow.worker.remote.client.ExpiredDataCleanupRemote.Batch;
import com.taskflow.worker.remote.client.ExpiredDataCleanupRemote.Preview;
import com.taskflow.worker.remote.client.ExpiredDataCleanupRemote.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ExpiredDataCleanupHandlerTests {
    private static final String KEY = "stable-run:expired-data-cleanup";
    private static final Instant CUTOFF = Instant.parse("2026-08-11T00:00:00Z");
    private static final Map<String, String> HEADERS = Map.of("X-Taskflow-Token", "test-cleanup", "X-Request-Id", KEY);

    private final ExpiredDataCleanupRemote remote = mock(ExpiredDataCleanupRemote.class);
    private final ExecutionContext context = mock(ExecutionContext.class);
    private final ExpiredDataCleanupHandlerServiceImpl handler = new ExpiredDataCleanupHandlerServiceImpl(remote,
            new OrderHandlerProperties(false, "test-order", Duration.ofSeconds(1), Duration.ofSeconds(2)),
            "test-cleanup");

    @BeforeEach
    void setBudget() {
        when(context.businessIdempotencyKey("expired-data-cleanup")).thenReturn(KEY);
        when(context.executionDeadline()).thenReturn(Instant.now().plusSeconds(60));
    }

    @Test
    void defaultsToReadOnlyPreviewAndRejectsAmbiguousValues() {
        when(remote.select(90, 500, HEADERS)).thenReturn(new Preview(CUTOFF, 500, true));
        assertThat(handler.execute(snapshot("{}"), context))
                .contains("mode=PREVIEW", "eligibleCount=500", "deletedCount=0", "hasMore=true");
        verify(remote).select(90, 500, HEADERS);
        for (var value : List.of("{\"dryRun\":\"false\"}", "{\"dryRun\":null}", "{\"retentionDays\":89}",
                "{\"batchSize\":501}", "{\"maxBatches\":11}")) {
            assertThatThrownBy(() -> handler.execute(snapshot(value), context)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoMoreInteractions(remote);
    }

    @Test
    void retriesWithSameRunAndBatchKeysAndStopsAtConfiguredLimit() {
        var first = new Request(KEY, 90, 2, 1);
        var second = new Request(KEY, 90, 2, 2);
        when(remote.delete(first, HEADERS)).thenReturn(new Batch(CUTOFF, 1, 2, true));
        when(remote.delete(second, HEADERS)).thenThrow(new IllegalStateException("lost response"))
                .thenReturn(new Batch(CUTOFF, 2, 2, true));
        var config = snapshot("{\"batchSize\":2,\"maxBatches\":2,\"dryRun\":false}");
        assertThatThrownBy(() -> handler.execute(config, context))
                .isInstanceOfSatisfying(HandlerFailureException.class, error -> assertThat(error.retryable()).isTrue());
        assertThat(handler.execute(config, context))
                .contains("mode=DELETE", "deletedCount=4", "batches=2", "hasMore=true");
        verify(remote, times(2)).delete(first, HEADERS);
        verify(remote, times(2)).delete(second, HEADERS);
        verifyNoMoreInteractions(remote);
    }

    @Test
    void insufficientBudgetPreventsDeleteCall() {
        when(context.executionDeadline()).thenReturn(Instant.now());
        assertThatThrownBy(() -> handler.execute(snapshot("{\"dryRun\":false}"), context))
                .isInstanceOfSatisfying(HandlerFailureException.class, error -> assertThat(error.retryable()).isTrue());
        verifyNoInteractions(remote);
    }

    private Snapshot snapshot(String payload) {
        return new Snapshot(1, 1, "cleanup", "expiredDataCleanup", new JsonMapper().readTree(payload),
                60, 2, 1, "MANUAL", null, "Asia/Shanghai", "SKIP", false, null);
    }
}

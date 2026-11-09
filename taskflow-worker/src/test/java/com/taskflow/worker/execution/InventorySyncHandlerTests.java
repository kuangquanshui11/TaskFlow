package com.taskflow.worker.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.exception.HandlerFailureException;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.inventory.service.impl.InventorySyncHandlerServiceImpl;
import com.taskflow.worker.order.config.OrderHandlerProperties;
import com.taskflow.worker.remote.client.InventorySyncRemote;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventorySyncHandlerTests {
    private final InventorySyncRemote remote = mock(InventorySyncRemote.class);
    private final ExecutionContext context = mock(ExecutionContext.class);
    private final InventorySyncHandlerServiceImpl handler = new InventorySyncHandlerServiceImpl(remote,
            new OrderHandlerProperties(false, "test-token", Duration.ofSeconds(1), Duration.ofSeconds(2)));

    @Test
    void sendsRunScopedKeyAndReturnsPersistedBatchSummary() {
        active();
        var id = UUID.randomUUID().toString();
        when(remote.insert(any(), any())).thenReturn(new InventorySyncRemote.Result(id, 12, 5, "APPLIED", 2));
        assertThat(handler.execute(snapshot("{\"warehouseId\":12}"), context))
                .contains("batchId=" + id, "warehouseId=12", "sourceVersion=5", "outcome=APPLIED", "updatedCount=2");
        verify(remote).insert(eq(new InventorySyncRemote.Request("stable-run:inventory-sync", 12)),
                eq(Map.of("X-Taskflow-Token", "test-token", "X-Request-Id", "stable-run:inventory-sync")));
        assertThat(handler.keys()).containsExactly("inventorySync");
    }

    @Test
    void invalidWarehouseOrInsufficientBudgetDoesNotCallSource() {
        for (var payload : List.of("{}", "{\"warehouseId\":0}", "{\"warehouseId\":1.5}",
                "{\"warehouseId\":\"12\"}", "{\"warehouseId\":9223372036854775808}")) {
            assertThatThrownBy(() -> handler.execute(snapshot(payload), context)).isInstanceOf(IllegalArgumentException.class);
        }
        active();
        when(context.executionDeadline()).thenReturn(Instant.now());
        assertThatThrownBy(() -> handler.execute(snapshot("{\"warehouseId\":12}"), context))
                .isInstanceOfSatisfying(HandlerFailureException.class, error -> assertThat(error.retryable()).isTrue());
        verifyNoInteractions(remote);
    }

    @Test
    void unavailableSourceCanBeRetriedButInvalidResponseIsTerminal() {
        active();
        when(remote.insert(any(), any())).thenThrow(new IllegalStateException("simulated connection loss"))
                .thenReturn(new InventorySyncRemote.Result(UUID.randomUUID().toString(), 13, 1, "APPLIED", 2));
        var snapshot = snapshot("{\"warehouseId\":12}");
        assertThatThrownBy(() -> handler.execute(snapshot, context))
                .isInstanceOfSatisfying(HandlerFailureException.class, error -> assertThat(error.retryable()).isTrue());
        assertThatThrownBy(() -> handler.execute(snapshot, context))
                .isInstanceOfSatisfying(HandlerFailureException.class, error -> assertThat(error.retryable()).isFalse());
    }

    private void active() {
        when(context.businessIdempotencyKey("inventory-sync")).thenReturn("stable-run:inventory-sync");
        when(context.executionDeadline()).thenReturn(Instant.now().plusSeconds(60));
    }

    private Snapshot snapshot(String payload) {
        return new Snapshot(1, 1, "inventory", "inventorySync", new JsonMapper().readTree(payload),
                60, 2, 1, "MANUAL", null, "Asia/Shanghai", "SKIP", false, null);
    }
}

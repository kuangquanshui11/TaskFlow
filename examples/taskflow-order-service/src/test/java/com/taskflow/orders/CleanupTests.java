package com.taskflow.orders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Request;
import com.taskflow.orders.cleanup.mapper.CleanupMapper;
import com.taskflow.orders.cleanup.service.ICleanupService;
import com.taskflow.orders.cleanup.service.ICleanupRetentionService;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.RetryRequest;
import com.taskflow.orders.inventory.mapper.StockCache;
import com.taskflow.orders.inventory.service.IInventoryService;
import com.taskflow.orders.inventory.service.IStockOutboxOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "test-cleanup"})
class CleanupTests {
    private static final String PATH = "/internal/v1/expired-data";
    private static final String TOKEN = "test-order-cleanup";
    private static final String OPERATIONS = "/internal/v1/order-operations";
    private static final String OPERATIONS_TOKEN = "test-order-operations";

    @Autowired ICleanupService cleanup;
    @Autowired IInventoryService inventory;
    @Autowired IStockOutboxOperationsService operations;
    @Autowired ICleanupRetentionService retention;
    @Autowired JdbcTemplate db;
    @MockitoBean StockCache cache;
    @MockitoSpyBean CleanupMapper mapper;
    @LocalServerPort int port;

    @BeforeEach
    void clearFixtures() {
        String url = db.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        assertThat(url).matches("jdbc:h2:mem:orders-[a-z0-9-]+(?:;.*)?"
                + "|jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/taskflow_it_order_[a-zA-Z0-9]+(?:\\?.*)?");
        db.update("DELETE FROM expired_data_cleanup_batch");
        db.update("DELETE FROM expired_data_cleanup_run");
        db.update("DELETE FROM stock_outbox_history");
        db.update("DELETE FROM stock_outbox_failure");
        db.update("DELETE FROM stock_outbox");
    }

    @Test
    void previewsAndDeletesOnlyExpiredSuccessHistoryAndReturnsOriginalReceipts() {
        var cutoff = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(90, ChronoUnit.DAYS);
        db.update("""
                INSERT INTO expired_data_cleanup_run(request_key, cutoff, retention_days, batch_size)
                VALUES ('stable-cleanup', ?, 90, 1)
                """, Timestamp.from(cutoff));
        history("oldest", cutoff.minusSeconds(60));
        history("older", cutoff.minusSeconds(1));
        history("at-boundary", cutoff);
        history("recent", cutoff.plusSeconds(60));
        pending("pending", cutoff, 0);
        pending("exhausted", cutoff, 10);

        var preview = cleanup.select(90, 1);
        assertThat(preview.eligibleCount()).isEqualTo(1);
        assertThat(preview.hasMore()).isTrue();
        assertThat(historyIds()).containsExactly("at-boundary", "older", "oldest", "recent");

        var request = new Request("stable-cleanup", 90, 1, 1);
        var first = cleanup.delete(request);
        assertThat(first.cutoff()).isEqualTo(cutoff);
        assertThat(first.deletedCount()).isEqualTo(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(cleanup.delete(request)).isEqualTo(first);
        assertThat(historyIds()).containsExactly("at-boundary", "older", "recent");
        assertThatThrownBy(() -> cleanup.delete(new Request("stable-cleanup", 90, 2, 1)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).isEqualTo("IDEMPOTENCY_CONFLICT"));

        var second = cleanup.delete(new Request("stable-cleanup", 90, 1, 2));
        assertThat(second.cutoff()).isEqualTo(cutoff);
        assertThat(second.deletedCount()).isEqualTo(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(historyIds()).containsExactly("at-boundary", "recent");
        assertThat(db.queryForList("SELECT id FROM stock_outbox ORDER BY id", String.class))
                .containsExactly("exhausted", "pending");
        assertThatThrownBy(() -> cleanup.delete(new Request("stable-cleanup", 90, 1, 3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).isEqualTo("CLEANUP_BATCH_OUT_OF_SEQUENCE"));
    }

    @Test
    void concurrentRequestsReplayOneBatchAndDifferentRunsDoNotCountTheSameRows() throws Exception {
        var old = Instant.now().minus(100, ChronoUnit.DAYS);
        history("one", old);
        history("two", old);
        history("three", old);
        var request = new Request("concurrent-cleanup", 90, 1, 1);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(3)) {
            var first = threads.submit(() -> { start.await(); return cleanup.delete(request); });
            var replay = threads.submit(() -> { start.await(); return cleanup.delete(request); });
            var other = threads.submit(() -> {
                start.await();
                return cleanup.delete(new Request("other-cleanup", 90, 1, 1));
            });
            start.countDown();
            var result = first.get(10, TimeUnit.SECONDS);
            assertThat(replay.get(10, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(result.deletedCount()).isEqualTo(1);
            assertThat(other.get(10, TimeUnit.SECONDS).deletedCount()).isEqualTo(1);
            assertThat(historyIds()).hasSize(1);
        }
    }

    @Test
    void successfulCacheRefreshRecordsHistoryAndFailedRefreshKeepsPendingRow() {
        inventory.insert(760001, 9);
        makePublishable(760001);
        var id = db.queryForObject("SELECT id FROM stock_outbox WHERE sku_id = 760001", String.class);
        inventory.publishPending();
        inventory.publishPending();
        assertThat(historyIds()).containsExactly(id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM stock_outbox", Long.class)).isZero();

        doThrow(new IllegalStateException("Redis unavailable")).when(cache).put(any(), any());
        inventory.insert(760002, 3);
        makePublishable(760002);
        inventory.publishPending();
        assertThat(historyIds()).containsExactly(id);
        assertThat(db.queryForObject("SELECT attempts FROM stock_outbox WHERE sku_id = 760002", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failedReceiptRollsBackDeletionAndRetryKeepsBoundCutoff() {
        history("rollback", Instant.now().minus(100, ChronoUnit.DAYS));
        var request = new Request("cleanup-rollback", 90, 500, 1);
        doThrow(new IllegalStateException("simulated receipt failure")).when(mapper).save(any(), any());
        assertThatThrownBy(() -> cleanup.delete(request)).isInstanceOf(IllegalStateException.class);
        assertThat(historyIds()).containsExactly("rollback");
        var bound = db.queryForObject("SELECT cutoff FROM expired_data_cleanup_run WHERE request_key = ?",
                Timestamp.class, request.requestKey()).toInstant();

        reset(mapper);
        var retry = cleanup.delete(request);
        assertThat(retry.cutoff()).isEqualTo(bound);
        assertThat(retry.deletedCount()).isEqualTo(1);
        assertThat(historyIds()).isEmpty();
    }

    @Test
    void internalHttpRequiresSeparateTokenAndEnforcesLimits() throws Exception {
        history("http-old", Instant.now().minus(100, ChronoUnit.DAYS));
        var input = """
                {"requestKey":"http-cleanup","retentionDays":90,"batchSize":1,"batchNumber":1}
                """;
        try (var client = HttpClient.newHttpClient()) {
            assertThat(call(client, PATH + "/delete", "test-order-worker", input).statusCode()).isEqualTo(401);
            assertThat(call(client, PATH + "/delete", "test-order-api", input).statusCode()).isEqualTo(401);
            assertThat(call(client, "/internal/v1/%65xpired-data/delete", "test-order-api", input).statusCode())
                    .isEqualTo(401);
            assertThat(call(client, PATH + ";test/delete", "test-order-api", input).statusCode()).isEqualTo(401);
            assertThat(call(client, PATH + "/delete", TOKEN, input.replace(":90", ":89")).statusCode()).isEqualTo(400);
            assertThat(call(client, PATH + "/select?retentionDays=90&batchSize=501", TOKEN, null).statusCode())
                    .isEqualTo(400);
            var preview = call(client, PATH + "/select?retentionDays=90&batchSize=1", TOKEN, null);
            assertThat(preview.statusCode()).isEqualTo(200);
            assertThat(preview.body()).contains("\"eligibleCount\":1");
            assertThat(historyIds()).containsExactly("http-old");
            var deleted = call(client, PATH + "/delete", TOKEN, input);
            assertThat(deleted.statusCode()).isEqualTo(200);
            assertThat(deleted.body()).contains("\"deletedCount\":1");
            assertThat(call(client, PATH + "/delete", TOKEN, input).body()).isEqualTo(deleted.body());
            assertThat(historyIds()).isEmpty();
        }
    }

    @Test
    void exhaustedDeliveryHasDiagnosisAndConcurrentCompensationReplaysOneReceipt() throws Exception {
        inventory.insert(760003, 6);
        var eventId = db.queryForObject("SELECT id FROM stock_outbox WHERE sku_id = 760003", String.class);
        db.update("UPDATE stock_outbox SET attempts = 9 WHERE id = ?", eventId);
        doThrow(new IllegalStateException("Redis unavailable")).when(cache).put(any(), any());
        inventory.publishPending();
        var failure = operations.select(eventId, 0, 10, false).getFirst();
        assertThat(failure.errorCode()).isEqualTo("CACHE_WRITE_FAILED");
        assertThat(failure.lastFailedAt()).isNotNull();
        assertThat(failure.attempts()).isEqualTo(10);
        assertThat(failure.generation()).isZero();

        var retry = new RetryRequest(0L, "Redis restored");
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> { start.await(); return operations.retry(eventId, retry); });
            var replay = threads.submit(() -> { start.await(); return operations.retry(eventId, retry); });
            start.countDown();
            assertThat(replay.get(10, TimeUnit.SECONDS)).isEqualTo(first.get(10, TimeUnit.SECONDS));
        }
        assertThat(operations.retry(eventId, retry).queuedGeneration()).isEqualTo(1);
        assertThatThrownBy(() -> operations.retry(eventId, new RetryRequest(0L, "different reason")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(operations.select(eventId, failure.id(), 10, true)).isEmpty();
        assertThat(operations.select(eventId, 0, 10, false)).hasSize(1);

        reset(cache);
        inventory.publishPending();
        assertThat(historyIds()).containsExactly(eventId);
        assertThat(operations.select(eventId, 0, 10, false)).isEmpty();
        assertThat(operations.select(eventId, 0, 10, true).getFirst().resolvedAt()).isNotNull();
        assertThat(operations.retry(eventId, retry).queuedGeneration()).isEqualTo(1);
    }

    @Test
    void lateSuccessFromOldGenerationCannotFinishCompensatedDelivery() throws Exception {
        inventory.insert(760004, 8);
        var eventId = db.queryForObject("SELECT id FROM stock_outbox WHERE sku_id = 760004", String.class);
        db.update("UPDATE stock_outbox SET attempts = 9 WHERE id = ?", eventId);
        makePublishable(760004);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (entered.getCount() == 0) throw new IllegalStateException("Redis unavailable");
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(cache).put(any(), any());

        try (var threads = Executors.newSingleThreadExecutor()) {
            var stale = threads.submit(inventory::publishPending);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                inventory.publishPending();
                operations.retry(eventId, new RetryRequest(0L, "Retry after outage"));
            } finally {
                release.countDown();
            }
            stale.get(10, TimeUnit.SECONDS);
        }
        assertThat(historyIds()).isEmpty();
        assertThat(db.queryForObject("SELECT generation FROM stock_outbox WHERE id = ?", Long.class, eventId))
                .isEqualTo(1L);
        reset(cache);
        makePublishable(760004);
        inventory.publishPending();
        assertThat(historyIds()).containsExactly(eventId);
    }

    @Test
    void retentionPreviewAndPrunePreserveInFlightDataAndBlockExpiredReplay() throws Exception {
        var expired = new Request("expired-run", 365, 500, 1);
        var fresh = new Request("fresh-run", 90, 500, 1);
        history("initial", Instant.now().minus(400, ChronoUnit.DAYS));
        cleanup.delete(expired);
        cleanup.delete(new Request("second-expired-run", 90, 1, 1));
        cleanup.delete(fresh);
        history("new-candidate", Instant.now().minus(400, ChronoUnit.DAYS));
        var old = Instant.now().minus(91, ChronoUnit.DAYS);
        db.update("UPDATE expired_data_cleanup_run SET last_activity_at = ? WHERE request_key = 'expired-run'",
                Timestamp.from(old.minusSeconds(60)));
        db.update("UPDATE expired_data_cleanup_run SET last_activity_at = ? WHERE request_key = 'second-expired-run'",
                Timestamp.from(old));
        var unresolved = UUID.randomUUID().toString();
        pending(unresolved, old, 10);
        pending("in-flight", old, 0);
        db.update("UPDATE stock_outbox SET next_attempt_at = ? WHERE id = 'in-flight'", Timestamp.from(Instant.now().plusSeconds(600)));
        inventory.publishPending();
        // Keep the candidate in-flight and create a resolved diagnosis with a separate event ID.
        assertThat(operations.select(unresolved, 0, 10, false).getFirst().errorCode()).isEqualTo("PREVIOUS_FAILURE_UNKNOWN");
        var resolved = UUID.randomUUID().toString();
        db.update("""
                INSERT INTO stock_outbox_failure(event_id, generation, sku_id, attempts, detected_at, error_code, resolved_at)
                VALUES (?, 0, 760000, 10, ?, 'CACHE_WRITE_FAILED', ?)
                """, resolved, Timestamp.from(old), Timestamp.from(old));

        try (var client = HttpClient.newHttpClient()) {
            var path = OPERATIONS + "/cleanup-retention/select";
            assertThat(call(client, path, TOKEN, null).statusCode()).isEqualTo(401);
            var response = call(client, path, OPERATIONS_TOKEN, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"cleanupRuns\":{\"eligibleCount\":1,\"hasMore\":true}",
                    "\"resolvedFailures\":{\"eligibleCount\":1,\"hasMore\":false}");
            var rejected = call(client, PATH + "/delete", TOKEN,
                    "{\"requestKey\":\"expired-run\",\"retentionDays\":365,\"batchSize\":500,\"batchNumber\":1}");
            assertThat(rejected.statusCode()).isEqualTo(409);
            assertThat(rejected.body()).contains("CLEANUP_REQUEST_EXPIRED");
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM expired_data_cleanup_batch", Integer.class)).isEqualTo(3);
        assertExpired(expired);

        doThrow(new IllegalStateException("simulated expiration write failure")).when(mapper).expireRun("expired-run");
        assertThatThrownBy(retention::prune).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM expired_data_cleanup_batch", Integer.class)).isEqualTo(3);
        reset(mapper);

        retention.prune();
        assertExpired(expired);
        assertThat(db.queryForObject("SELECT expired FROM expired_data_cleanup_run WHERE request_key = 'expired-run'", Boolean.class)).isTrue();
        assertThat(db.queryForList("SELECT request_key FROM expired_data_cleanup_batch ORDER BY request_key", String.class))
                .containsExactly("fresh-run", "second-expired-run");
        assertThat(operations.select(resolved, 0, 10, true)).isEmpty();
        assertThat(operations.select(unresolved, 0, 10, true)).hasSize(1);
        assertThat(db.queryForList("SELECT id FROM stock_outbox", String.class)).containsExactlyInAnyOrder(unresolved, "in-flight");
        assertThat(db.queryForObject("SELECT attempts FROM stock_outbox WHERE id = 'in-flight'", Integer.class)).isZero();
        assertThat(historyIds()).containsExactly("new-candidate");
        assertThat(cleanup.delete(fresh).deletedCount()).isZero();
    }

    @Test
    void operationsApiRequiresItsOwnTokenAndAReasonForCompensation() throws Exception {
        var eventId = UUID.randomUUID().toString();
        pending(eventId, Instant.now().minusSeconds(60), 10);
        inventory.publishPending();
        var path = OPERATIONS + "/stock-outbox-failures";
        var input = "{\"generation\":0,\"reason\":\"Redis restored\"}";
        try (var client = HttpClient.newHttpClient()) {
            assertThat(call(client, path + "/select", "test-order-worker", null).statusCode()).isEqualTo(401);
            assertThat(call(client, path + "/select", "test-order-api", null).statusCode()).isEqualTo(401);
            assertThat(call(client, path.replace("order-operations", "%6frder-operations") + "/select",
                    "test-order-api", null).statusCode()).isEqualTo(401);
            assertThat(call(client, path + "/select?limit=101", OPERATIONS_TOKEN, null).statusCode()).isEqualTo(400);
            var selected = call(client, path + "/select?eventId=" + eventId, OPERATIONS_TOKEN, null);
            assertThat(selected.statusCode()).isEqualTo(200);
            assertThat(selected.body()).contains(eventId, "PREVIOUS_FAILURE_UNKNOWN");
            var update = path + "/" + eventId + "/updateRetry";
            assertThat(call(client, update, TOKEN, input).statusCode()).isEqualTo(401);
            assertThat(call(client, update, OPERATIONS_TOKEN, input.replace("Redis restored", " ")).statusCode()).isEqualTo(400);
            var queued = call(client, update, OPERATIONS_TOKEN, input);
            assertThat(queued.statusCode()).isEqualTo(200);
            assertThat(call(client, update, OPERATIONS_TOKEN, input).body()).isEqualTo(queued.body());
        }
    }

    private void assertExpired(Request request) {
        assertThatThrownBy(() -> cleanup.delete(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getReason()).isEqualTo("CLEANUP_REQUEST_EXPIRED"));
    }

    private void history(String id, Instant publishedAt) {
        db.update("INSERT INTO stock_outbox_history(id,sku_id,published_at) VALUES (?,760000,?)",
                id, Timestamp.from(publishedAt));
    }

    private void pending(String id, Instant created, int attempts) {
        db.update("INSERT INTO stock_outbox(id,sku_id,created_at,next_attempt_at,attempts) VALUES (?,760000,?,?,?)",
                id, Timestamp.from(created), Timestamp.from(created), attempts);
    }

    private void makePublishable(long skuId) {
        db.update("UPDATE stock_outbox SET next_attempt_at = ? WHERE sku_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), skuId);
    }

    private List<String> historyIds() {
        return db.queryForList("SELECT id FROM stock_outbox_history ORDER BY id", String.class);
    }

    private HttpResponse<String> call(HttpClient client, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Taskflow-Token", token);
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

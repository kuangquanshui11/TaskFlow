package com.taskflow.orders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Outcome;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Request;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Result;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Stock;
import com.taskflow.orders.inventory.service.IInventoryService;
import com.taskflow.orders.inventory.service.IInventorySyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InventorySyncTests {
    private static final String PATH = "/internal/v1/inventory-sync";
    private static final String WORKER_TOKEN = "test-order-worker";

    @TempDir static Path sourceDir;

    @DynamicPropertySource
    static void configureSource(DynamicPropertyRegistry properties) {
        properties.add("order.inventory-sync.enabled", () -> true);
        properties.add("order.inventory-sync.source", () -> "sample");
        properties.add("order.inventory-sync.sample-source", () -> sourceDir.resolve("snapshot.json").toUri().toString());
    }

    @Autowired IInventorySyncService sync;
    @Autowired IInventoryService inventory;
    @Autowired JdbcTemplate db;
    @Autowired JsonMapper json;
    @LocalServerPort int port;

    @Test
    void returnsCommittedBatchOnReplayAndRejectsOlderVersionsWithoutOverwriting() throws Exception {
        inventory.insert(12001, 23);
        snapshot(12, 1, """
                {"skuId":12001,"quantity":80},{"skuId":12002,"quantity":35}
                """);
        var originalRequest = new Request("lost-response", 12);
        var committed = sync.insert(originalRequest);
        assertThat(committed.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(committed.updatedCount()).isEqualTo(2);

        snapshot(12, 3, """
                {"skuId":12001,"quantity":17}
                """);
        assertThat(sync.insert(originalRequest)).isEqualTo(committed);
        assertThat(sync.insert(new Request("new-version", 12)).sourceVersion()).isEqualTo(3);
        // 升级前最后一批只有 SKU 上的版本标记，未提交的旧 SKU 不参与该版摘要。
        db.update("UPDATE inventory_sync_warehouse SET fingerprint=NULL WHERE source_id='sample' AND warehouse_id=12");
        assertThat(sync.insert(new Request("same-version", 12)).outcome()).isEqualTo(Outcome.UNCHANGED);

        snapshot(12, 2, """
                {"skuId":12001,"quantity":90},{"skuId":12003,"quantity":28}
                """);
        var late = sync.insert(new Request("old-version", 12));
        assertThat(late.outcome()).isEqualTo(Outcome.STALE);
        assertThat(late.updatedCount()).isZero();
        assertThat(sync.select(12, 12001)).isEqualTo(new Stock(12, 12001, 17, 3));
        assertThat(sync.select(12, 12002)).isEqualTo(new Stock(12, 12002, 35, 1));
        assertThatThrownBy(() -> sync.select(12, 12003)).isInstanceOf(ResponseStatusException.class);
        assertThat(inventory.select(12001).available()).isEqualTo(23);
        assertThatThrownBy(() -> sync.insert(new Request("lost-response", 13)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
    }

    @Test
    void concurrentIdenticalRequestsCommitOnlyOneBatch() throws Exception {
        snapshot(42, 5, """
                {"skuId":42001,"quantity":12}
                """);
        var request = new Request("concurrent-inventory", 42);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> { start.await(); return sync.insert(request); });
            var second = threads.submit(() -> { start.await(); return sync.insert(request); });
            start.countDown();
            var result = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM inventory_sync_batch WHERE warehouse_id = 42", Long.class))
                    .isEqualTo(1L);
        }
    }

    @Test
    void internalHttpValidatesIdentityAndSourceAndReadsPersistedMirror() throws Exception {
        snapshot(52, 1, """
                {"skuId":52001,"quantity":6},{"skuId":52001,"quantity":7}
                """);
        var request = "{\"warehouseId\":52,\"requestKey\":\"inventory-http\"}";
        try (var http = HttpClient.newHttpClient()) {
            assertThat(call(http, "/insert", "test-order-api", request).statusCode()).isEqualTo(401);
            assertThat(call(http, "/insert", WORKER_TOKEN, "{\"warehouseId\":0}").statusCode()).isEqualTo(400);
            assertThat(call(http, "/insert", WORKER_TOKEN, request).statusCode()).isEqualTo(503);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM inventory_sync_batch WHERE warehouse_id = 52", Long.class))
                    .isZero();

            snapshot(52, 1, """
                    {"skuId":52001,"quantity":7}
                    """);
            assertThat(call(http, "/insert", WORKER_TOKEN, request.replace("52", "53")).statusCode()).isEqualTo(404);
            var applied = call(http, "/insert", WORKER_TOKEN, request);
            assertThat(applied.statusCode()).isEqualTo(200);
            assertThat(json.readValue(applied.body(), Result.class).outcome()).isEqualTo(Outcome.APPLIED);
            var read = call(http, "/select?warehouseId=52&skuId=52001", WORKER_TOKEN, null);
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(json.readValue(read.body(), Stock.class)).isEqualTo(new Stock(52, 52001, 7, 1));
        }
    }

    private void snapshot(long warehouseId, long version, String items) throws Exception {
        Files.writeString(sourceDir.resolve("snapshot.json"), """
                {"warehouseId":%d,"sourceVersion":%d,"items":[%s]}
                """.formatted(warehouseId, version, items));
    }

    private HttpResponse<String> call(HttpClient http, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + path))
                .timeout(Duration.ofSeconds(5)).header("X-Taskflow-Token", token);
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

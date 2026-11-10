package com.taskflow.orders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Outcome;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Request;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Result;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Status;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Stock;
import com.taskflow.orders.inventory.service.IInventoryService;
import com.taskflow.orders.inventory.service.IInventorySyncService;
import com.taskflow.orders.order.dto.OrderProtocol.Create;
import com.taskflow.orders.order.service.IOrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
class WmsInventorySyncTests {
    private static final String PATH = "/internal/v1/inventory-sync";
    private static final String WMS_TOKEN = "test-wms-read";
    private static final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private static HttpServer wms;
    private static ExecutorService executor;

    @DynamicPropertySource
    static void configureSource(DynamicPropertyRegistry properties) throws IOException {
        replies.clear();
        calls.clear();
        wms = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        wms.setExecutor(executor);
        wms.createContext("/snapshot", WmsInventorySyncTests::reply);
        wms.start();
        String url = "http://127.0.0.1:" + wms.getAddress().getPort() + "/snapshot";
        properties.add("order.inventory-sync.enabled", () -> true);
        properties.add("order.inventory-sync.source", () -> "wms");
        properties.add("order.inventory-sync.wms-source-id", () -> "wms");
        properties.add("order.inventory-sync.wms-url", () -> url);
        properties.add("order.inventory-sync.wms-token", () -> WMS_TOKEN);
        properties.add("order.inventory-sync.wms-request-timeout", () -> "1s");
        properties.add("order.inventory-sync.wms-allow-loopback-http", () -> true);
    }

    @AfterAll static void stop() {
        if (wms != null) wms.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Autowired IInventorySyncService sync;
    @Autowired IInventoryService inventory;
    @Autowired IOrderService orders;
    @Autowired JdbcTemplate db;
    @Autowired JsonMapper json;
    @LocalServerPort int port;

    @Test
    void fullSnapshotReconcilesMissingSkuAndZeroWithoutChangingReservations() {
        inventory.insert(61001, 23);
        orders.insert(new Create("wms-reserved-order", 61001, 5));
        db.update("INSERT INTO inventory_sync_warehouse(warehouse_id,source_version) VALUES(61,20)");
        db.update("INSERT INTO inventory_sync_stock(warehouse_id,sku_id,quantity,source_version) VALUES(61,61002,99,20)");
        db.update("""
                INSERT INTO inventory_sync_batch(id,request_key,warehouse_id,source_version,outcome,updated_count)
                VALUES('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','sample-before-switch',61,20,'APPLIED',1)
                """);
        assertThat(sync.insert(new Request("sample-before-switch", 61)).sourceId()).isEqualTo("sample");
        assertThat(calls.containsKey("warehouseId=61")).isFalse();

        snapshot(61, 1, 3, """
                {"skuId":61001,"quantity":80},{"skuId":61002,"quantity":35},{"skuId":61003,"quantity":20}
                """);
        var request = new Request("wms-initial", 61);
        var initial = sync.insert(request);
        assertThat(initial.sourceId()).isEqualTo("wms");
        assertThat(initial.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(initial.updatedCount()).isEqualTo(3);

        snapshot(61, 3, 2, """
                {"skuId":61001,"quantity":0},{"skuId":61003,"quantity":17}
                """);
        assertThat(sync.insert(request)).isEqualTo(initial);
        assertThat(calls.get("warehouseId=61")).hasValue(1);
        var next = sync.insert(new Request("wms-v3", 61));
        assertThat(next.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(next.updatedCount()).isEqualTo(3);
        assertThat(sync.select(61, 61001)).isEqualTo(new Stock(61, 61001, 0, 3));
        assertThatThrownBy(() -> sync.select(61, 61002)).isInstanceOf(ResponseStatusException.class);

        snapshot(61, 3, 2, """
                {"skuId":61003,"quantity":17},{"skuId":61001,"quantity":0}
                """);
        assertThat(sync.insert(new Request("wms-reordered", 61)).outcome()).isEqualTo(Outcome.UNCHANGED);
        snapshot(61, 3, 1, "{\"skuId\":61001,\"quantity\":7}");
        assertThatThrownBy(() -> sync.insert(new Request("wms-conflict", 61)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).isEqualTo("INVENTORY_VERSION_CONFLICT");
                });
        snapshot(61, 2, 1, "{\"skuId\":61001,\"quantity\":50}");
        assertThat(sync.insert(new Request("wms-old", 61)).outcome()).isEqualTo(Outcome.STALE);
        var status = sync.selectStatus(61);
        assertThat(status.sourceVersion()).isEqualTo(3);
        assertThat(status.skuCount()).isEqualTo(2);
        assertThat(status.lastSyncedAt()).isNotNull();
        assertThat(status.lastFailureCode()).isEqualTo("INVENTORY_VERSION_CONFLICT");
        assertThat(status.lastFailureRequestKey()).isEqualTo("wms-conflict");
        assertThat(sync.select(61, 61001)).isEqualTo(new Stock(61, 61001, 0, 3));
        assertThat(db.queryForObject("""
                SELECT quantity FROM inventory_sync_stock WHERE source_id='sample' AND warehouse_id=61 AND sku_id=61002
                """, Long.class)).isEqualTo(99);
        assertThat(inventory.select(61001).available()).isEqualTo(18);
        assertThat(inventory.select(61001).reserved()).isEqualTo(5);
    }

    @Test
    void incompleteAndMalformedResponsesPreserveMirrorButExplicitEmptyClearsIt() throws Exception {
        snapshot(62, 1, 1, "{\"skuId\":62001,\"quantity\":12}");
        sync.insert(new Request("wms-validate-initial", 62));
        var valid = full(62, 2, 1, "{\"skuId\":62001,\"quantity\":15}");
        var invalid = java.util.List.of(
                valid.replace("\"complete\":true", "\"complete\":false"),
                valid.replace("\"totalItems\":1", "\"totalItems\":2"),
                valid.replace("\"quantity\":15", "\"quantity\":1.5"),
                valid.replace("\"warehouseId\":62", "\"warehouseId\":63"),
                full(62, 2, 2, "{\"skuId\":62001,\"quantity\":15},{\"skuId\":62001,\"quantity\":3}"),
                full(62, 2, 0, "").replace("\"complete\":true,", ""),
                valid + "{}");
        try (var client = HttpClient.newHttpClient()) {
            for (var body : invalid) {
                replies.put("warehouseId=62", new Reply(200, body, false));
                var response = post(client, 62, "wms-invalid", "test-order-worker");
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("INVALID_INVENTORY_SNAPSHOT");
                assertThat(sync.select(62, 62001)).isEqualTo(new Stock(62, 62001, 12, 1));
            }
            assertThat(db.queryForObject("SELECT COUNT(*) FROM inventory_sync_batch WHERE warehouse_id=62", Long.class))
                    .isEqualTo(1);
            snapshot(62, 2, 0, "");
            var response = post(client, 62, "wms-invalid", "test-order-worker");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readValue(response.body(), Result.class).updatedCount()).isEqualTo(1);
            assertThat(sync.selectStatus(62).skuCount()).isZero();
            assertThat(sync.selectStatus(62).sourceVersion()).isEqualTo(2);
        }
    }

    @Test
    void authAndTimeoutAreDistinguishedAndDiagnosticsUseInternalIdentity() throws Exception {
        snapshot(63, 1, 1, "{\"skuId\":63001,\"quantity\":12}");
        try (var client = HttpClient.newHttpClient()) {
            assertThat(post(client, 63, "wms-auth-initial", "test-order-worker").statusCode()).isEqualTo(200);
            replies.put("warehouseId=63", new Reply(401, "", false));
            var denied = post(client, 63, "wms-auth-error", "test-order-worker");
            assertThat(denied.statusCode()).isEqualTo(422);
            assertThat(json.readTree(denied.body()).path("code").asString()).isEqualTo("WMS_AUTH_FAILED");
            assertThat(sync.selectStatus(63).lastFailureCode()).isEqualTo("WMS_AUTH_FAILED");

            replies.put("warehouseId=63", new Reply(200, full(63, 2, 0, ""), true));
            var timeout = post(client, 63, "wms-timeout", "test-order-worker");
            assertThat(timeout.statusCode()).isEqualTo(503);
            assertThat(json.readTree(timeout.body()).path("code").asString()).isEqualTo("WMS_SOURCE_UNAVAILABLE");
            var uri = URI.create("http://127.0.0.1:" + port + PATH + "/selectStatus?warehouseId=63");
            assertThat(client.send(HttpRequest.newBuilder(uri).header("X-Taskflow-Token", "test-order-api").build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            var response = client.send(HttpRequest.newBuilder(uri).header("X-Taskflow-Token", "test-order-worker").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var status = json.readValue(response.body(), Status.class);
            assertThat(status.lastFailureCode()).isEqualTo("WMS_SOURCE_UNAVAILABLE");
            assertThat(status.lastFailureRequestKey()).isEqualTo("wms-timeout");
            assertThat(status.lastFailureAt()).isNotNull();
            assertThat(status.sourceVersion()).isEqualTo(1);
            assertThat(sync.select(63, 63001).quantity()).isEqualTo(12);
        }
    }

    private static void snapshot(long warehouseId, long version, int count, String items) {
        replies.put("warehouseId=" + warehouseId, new Reply(200, full(warehouseId, version, count, items), false));
    }

    private static String full(long warehouseId, long version, int count, String items) {
        return """
                {"warehouseId":%d,"sourceVersion":%d,"complete":true,"totalItems":%d,"items":[%s]}
                """.formatted(warehouseId, version, count, items);
    }

    private HttpResponse<String> post(HttpClient client, long warehouseId, String key, String token)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + "/insert"))
                .header("Content-Type", "application/json").header("X-Taskflow-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"warehouseId":%d,"requestKey":"%s"}
                        """.formatted(warehouseId, key))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void reply(HttpExchange exchange) throws IOException {
        try (exchange) {
            String query = exchange.getRequestURI().getRawQuery();
            if (!"GET".equals(exchange.getRequestMethod())
                    || !("Bearer " + WMS_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            calls.computeIfAbsent(query, key -> new AtomicInteger()).incrementAndGet();
            var reply = replies.get(query);
            if (reply == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (reply.delayBody()) Thread.sleep(2500);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Reply(int status, String body, boolean delayBody) { }
}

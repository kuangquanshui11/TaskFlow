package com.taskflow.orders.inventory.service.impl;

import java.io.IOException;
import java.util.List;
import com.taskflow.orders.config.OrderProperties;
import com.taskflow.orders.inventory.config.InventorySyncProperties;
import com.taskflow.orders.inventory.model.InventorySnapshot;
import com.taskflow.orders.inventory.service.IInventorySnapshotSource;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "order.inventory-sync.enabled", havingValue = "true")
@ConditionalOnProperty(name = "order.inventory-sync.source", havingValue = "wms")
public class HttpInventorySnapshotSource implements IInventorySnapshotSource {
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private final InventorySyncProperties properties;
    private final HttpUrl endpoint;
    private final OkHttpClient http;
    private final JsonMapper json = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public HttpInventorySnapshotSource(InventorySyncProperties properties, OrderProperties orders) {
        var url = properties.wmsUrl();
        boolean loopback = url != null && properties.wmsAllowLoopbackHttp()
                && "http".equalsIgnoreCase(url.getScheme()) && "127.0.0.1".equals(url.getHost());
        if (url == null || url.getHost() == null || !("https".equalsIgnoreCase(url.getScheme()) || loopback)
                || url.getUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new IllegalArgumentException("WMS endpoint requires HTTPS without embedded credentials, query or fragment");
        }
        var token = properties.wmsToken();
        if (token == null || !token.matches("[A-Za-z0-9._~+/-]+=*")
                || token.equals(orders.apiToken()) || token.equals(orders.workerToken())) {
            throw new IllegalArgumentException("WMS requires a dedicated bearer credential");
        }
        if (properties.wmsSourceId() == null || !properties.wmsSourceId().matches("wms(?:-[a-z0-9][a-z0-9-]{0,43})?")) {
            throw new IllegalArgumentException("WMS source identifier must be wms or wms-<identifier>");
        }
        if (properties.wmsConnectTimeout().toMillis() <= 0 || properties.wmsRequestTimeout().toMillis() <= 0) {
            throw new IllegalArgumentException("WMS timeouts must be positive");
        }
        this.properties = properties;
        endpoint = HttpUrl.get(url.toString());
        http = new OkHttpClient.Builder()
                .connectTimeout(properties.wmsConnectTimeout()).callTimeout(properties.wmsRequestTimeout())
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .build();
    }

    @Override public String sourceId() { return properties.wmsSourceId(); }
    @Override public boolean replacesMissingStock() { return true; }

    @Override
    public InventorySnapshot fetch(long warehouseId) {
        var url = endpoint.newBuilder().addQueryParameter("warehouseId", Long.toString(warehouseId)).build();
        var request = new Request.Builder().url(url).header("Accept", "application/json")
                .header("Authorization", "Bearer " + properties.wmsToken()).build();
        try (var response = http.newCall(request).execute()) {
            checkStatus(response.code());
            var body = response.body();
            if (body == null || body.contentLength() > MAX_BODY_BYTES) throw invalidSnapshot();
            var bytes = body.byteStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw invalidSnapshot();
            var full = json.readValue(bytes, FullSnapshot.class);
            if (full == null || !Boolean.TRUE.equals(full.complete()) || full.items() == null || full.totalItems() == null
                    || full.items().size() != full.totalItems()) throw invalidSnapshot();
            return new InventorySnapshot(full.warehouseId(), full.sourceVersion(), full.items());
        } catch (JacksonException malformed) {
            throw invalidSnapshot();
        } catch (IOException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "WMS_SOURCE_UNAVAILABLE");
        }
    }

    private void checkStatus(int code) {
        if (code == 200) return;
        if (code == 401 || code == 403)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "WMS_AUTH_FAILED");
        if (code == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WAREHOUSE_NOT_FOUND");
        if (code == 408 || code == 429 || code >= 500)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "WMS_SOURCE_UNAVAILABLE");
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "WMS_REQUEST_REJECTED");
    }

    private ResponseStatusException invalidSnapshot() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_INVENTORY_SNAPSHOT");
    }

    private record FullSnapshot(long warehouseId, long sourceVersion, Boolean complete,
                                Integer totalItems, List<InventorySnapshot.Item> items) { }
}

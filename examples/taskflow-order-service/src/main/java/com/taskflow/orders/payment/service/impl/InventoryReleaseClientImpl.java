package com.taskflow.orders.payment.service.impl;

import java.io.IOException;
import com.taskflow.orders.config.OrderProperties;
import com.taskflow.orders.payment.config.PaymentHttp;
import com.taskflow.orders.payment.config.PaymentWorkProperties;
import com.taskflow.orders.payment.dto.PaymentProtocol.Release;
import com.taskflow.orders.payment.service.IInventoryReleaseClient;
import com.taskflow.orders.payment.service.IWechatPayClient.RemoteFailure;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(prefix = "order.payment-work", name = "inventory-release-enabled", havingValue = "true")
public class InventoryReleaseClientImpl implements IInventoryReleaseClient {
    private final PaymentWorkProperties properties;
    private final JsonMapper json;
    private final OkHttpClient http;

    public InventoryReleaseClientImpl(PaymentWorkProperties properties, OrderProperties orders, JsonMapper json) {
        PaymentHttp.requireHttps(properties.inventoryReleaseUrl(), null);
        if (properties.inventoryReleaseToken() == null || properties.inventoryReleaseToken().isBlank()
                || properties.inventoryReleaseToken().equals(orders.apiToken())
                || properties.inventoryReleaseToken().equals(orders.workerToken())) {
            throw new IllegalArgumentException("Inventory release requires a dedicated credential");
        }
        this.properties = properties;
        this.json = json;
        this.http = PaymentHttp.client(properties.inventoryConnectTimeout(), properties.inventoryRequestTimeout());
    }

    @Override
    public void release(Release release) {
        var request = new Request.Builder().url(properties.inventoryReleaseUrl().toString())
                .header("Authorization", "Bearer " + properties.inventoryReleaseToken())
                .header("Idempotency-Key", release.eventId())
                .post(RequestBody.create(json.writeValueAsString(release), MediaType.get("application/json"))).build();
        try (var response = http.newCall(request).execute()) {
            int status = response.code();
            if (status != 200 && status != 204) {
                throw new RemoteFailure("INVENTORY_HTTP_" + status, status == 408 || status == 429 || status >= 500);
            }
        } catch (IOException failure) {
            throw new RemoteFailure("INVENTORY_UNCONFIRMED", true);
        }
    }
}

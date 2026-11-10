package com.taskflow.orders.inventory.service.impl;

import java.io.IOException;
import com.taskflow.orders.inventory.model.InventorySnapshot;
import com.taskflow.orders.inventory.service.IInventorySnapshotSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "order.inventory-sync.enabled", havingValue = "true")
@ConditionalOnProperty(name = "order.inventory-sync.source", havingValue = "sample", matchIfMissing = true)
public class SampleInventorySnapshotSource implements IInventorySnapshotSource {
    private final Resource source;
    private final JsonMapper json;

    public SampleInventorySnapshotSource(@Value("${order.inventory-sync.sample-source}") Resource source,
                                         JsonMapper json) {
        this.source = source;
        this.json = json;
    }

    @Override public String sourceId() { return "sample"; }
    @Override public boolean replacesMissingStock() { return false; }

    @Override
    public InventorySnapshot fetch(long warehouseId) {
        try (var input = source.getInputStream()) {
            return json.readValue(input, InventorySnapshot.class);
        } catch (IOException | JacksonException invalidSource) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVENTORY_SOURCE_UNAVAILABLE");
        }
    }
}

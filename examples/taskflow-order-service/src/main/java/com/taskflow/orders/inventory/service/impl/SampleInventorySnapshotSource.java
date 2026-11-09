package com.taskflow.orders.inventory.service.impl;

import java.io.IOException;
import java.util.HashSet;
import com.taskflow.orders.inventory.model.InventorySnapshot;
import com.taskflow.orders.inventory.service.IInventorySnapshotSource;
import jakarta.validation.Validator;
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
public class SampleInventorySnapshotSource implements IInventorySnapshotSource {
    private final Resource source;
    private final JsonMapper json;
    private final Validator validator;

    public SampleInventorySnapshotSource(@Value("${order.inventory-sync.sample-source}") Resource source,
                                         JsonMapper json, Validator validator) {
        this.source = source;
        this.json = json;
        this.validator = validator;
    }

    @Override
    public InventorySnapshot fetch(long warehouseId) {
        InventorySnapshot snapshot;
        try (var input = source.getInputStream()) {
            snapshot = json.readValue(input, InventorySnapshot.class);
        } catch (IOException | JacksonException invalidSource) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVENTORY_SOURCE_UNAVAILABLE");
        }
        if (snapshot == null || !validator.validate(snapshot).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_INVENTORY_SNAPSHOT");
        }
        if (snapshot.warehouseId() != warehouseId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WAREHOUSE_NOT_FOUND");
        }
        var skuIds = new HashSet<Long>();
        for (var item : snapshot.items()) {
            if (!skuIds.add(item.skuId())) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_INVENTORY_SNAPSHOT");
            }
        }
        return snapshot;
    }
}

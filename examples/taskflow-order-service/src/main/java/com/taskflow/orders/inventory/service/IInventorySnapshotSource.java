package com.taskflow.orders.inventory.service;

import com.taskflow.orders.inventory.model.InventorySnapshot;

public interface IInventorySnapshotSource {
    String sourceId();
    boolean replacesMissingStock();
    InventorySnapshot fetch(long warehouseId);
}

package com.taskflow.orders.inventory.service;

import com.taskflow.orders.inventory.model.InventorySnapshot;

public interface IInventorySnapshotSource {
    InventorySnapshot fetch(long warehouseId);
}

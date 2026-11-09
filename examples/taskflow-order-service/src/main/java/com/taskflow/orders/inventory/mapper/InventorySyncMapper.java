package com.taskflow.orders.inventory.mapper;

import java.util.List;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Result;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Stock;
import com.taskflow.orders.inventory.model.InventorySnapshot.Item;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventorySyncMapper {
    @Insert("""
            INSERT INTO inventory_sync_warehouse (warehouse_id, source_version) VALUES (#{warehouseId}, 0)
            ON DUPLICATE KEY UPDATE source_version = source_version
            """)
    void ensureWarehouse(long warehouseId);

    @Select("SELECT source_version FROM inventory_sync_warehouse WHERE warehouse_id = #{warehouseId} FOR UPDATE")
    long lockWarehouse(long warehouseId);

    @Update("UPDATE inventory_sync_warehouse SET source_version = #{sourceVersion} WHERE warehouse_id = #{warehouseId}")
    void updateVersion(long warehouseId, long sourceVersion);

    @Insert("""
            <script>
            INSERT INTO inventory_sync_stock (warehouse_id, sku_id, quantity, source_version) VALUES
            <foreach collection="items" item="item" separator=",">
              (#{warehouseId}, #{item.skuId}, #{item.quantity}, #{sourceVersion})
            </foreach>
            ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), source_version = VALUES(source_version)
            </script>
            """)
    void upsertStock(long warehouseId, long sourceVersion, List<Item> items);

    @Insert("""
            INSERT INTO inventory_sync_batch (id, request_key, warehouse_id, source_version, outcome, updated_count)
            VALUES (#{result.id}, #{requestKey}, #{result.warehouseId}, #{result.sourceVersion},
                    #{result.outcome}, #{result.updatedCount})
            """)
    void insertBatch(String requestKey, Result result);

    @Select("""
            SELECT id, warehouse_id, source_version, outcome, updated_count
            FROM inventory_sync_batch WHERE request_key = #{requestKey}
            """)
    Result batch(String requestKey);

    @Select("""
            SELECT warehouse_id, sku_id, quantity, source_version
            FROM inventory_sync_stock WHERE warehouse_id = #{warehouseId} AND sku_id = #{skuId}
            """)
    Stock stock(long warehouseId, long skuId);
}

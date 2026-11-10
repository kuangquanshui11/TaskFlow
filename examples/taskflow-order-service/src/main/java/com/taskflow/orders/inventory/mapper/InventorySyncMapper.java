package com.taskflow.orders.inventory.mapper;

import java.util.List;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Result;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Status;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Stock;
import com.taskflow.orders.inventory.model.InventorySnapshot.Item;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventorySyncMapper {
    record Version(long sourceVersion, String fingerprint) { }

    @Insert("""
            INSERT INTO inventory_sync_warehouse (source_id, warehouse_id, source_version) VALUES (#{sourceId}, #{warehouseId}, 0)
            ON DUPLICATE KEY UPDATE source_version = source_version
            """)
    void ensureWarehouse(String sourceId, long warehouseId);

    @Select("""
            SELECT source_version, fingerprint FROM inventory_sync_warehouse
            WHERE source_id = #{sourceId} AND warehouse_id = #{warehouseId} FOR UPDATE
            """)
    Version lockWarehouse(String sourceId, long warehouseId);

    @Update("""
            UPDATE inventory_sync_warehouse SET source_version = #{sourceVersion}, fingerprint = #{fingerprint},
                last_synced_at = CURRENT_TIMESTAMP(6)
            WHERE source_id = #{sourceId} AND warehouse_id = #{warehouseId}
            """)
    void confirmVersion(String sourceId, long warehouseId, long sourceVersion, String fingerprint);

    @Select("""
            SELECT sku_id, quantity FROM inventory_sync_stock
            WHERE source_id = #{sourceId} AND warehouse_id = #{warehouseId} AND source_version = #{sourceVersion}
            ORDER BY sku_id
            """)
    List<Item> versionItems(String sourceId, long warehouseId, long sourceVersion);

    @Delete("""
            <script>
            DELETE FROM inventory_sync_stock WHERE source_id = #{sourceId} AND warehouse_id = #{warehouseId}
            <if test="items.size > 0">
              AND sku_id NOT IN <foreach collection="items" item="item" open="(" separator="," close=")">#{item.skuId}</foreach>
            </if>
            </script>
            """)
    int removeMissingStock(String sourceId, long warehouseId, List<Item> items);

    @Insert("""
            <script>
            INSERT INTO inventory_sync_stock (source_id, warehouse_id, sku_id, quantity, source_version) VALUES
            <foreach collection="items" item="item" separator=",">
              (#{sourceId}, #{warehouseId}, #{item.skuId}, #{item.quantity}, #{sourceVersion})
            </foreach>
            ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), source_version = VALUES(source_version)
            </script>
            """)
    void upsertStock(String sourceId, long warehouseId, long sourceVersion, List<Item> items);

    @Insert("""
            INSERT INTO inventory_sync_batch (id, request_key, warehouse_id, source_version, outcome, updated_count, source_id)
            VALUES (#{result.id}, #{requestKey}, #{result.warehouseId}, #{result.sourceVersion},
                    #{result.outcome}, #{result.updatedCount}, #{result.sourceId})
            """)
    void insertBatch(String requestKey, Result result);

    @Select("""
            SELECT id, warehouse_id, source_version, outcome, updated_count, source_id
            FROM inventory_sync_batch WHERE request_key = #{requestKey}
            """)
    Result batch(String requestKey);

    @Select("""
            SELECT warehouse_id, sku_id, quantity, source_version
            FROM inventory_sync_stock WHERE source_id = #{sourceId} AND warehouse_id = #{warehouseId} AND sku_id = #{skuId}
            """)
    Stock stock(String sourceId, long warehouseId, long skuId);

    @Insert("""
            INSERT INTO inventory_sync_warehouse (source_id, warehouse_id, source_version, last_failure_at,
                last_failure_code, last_failure_request_key)
            VALUES (#{sourceId}, #{warehouseId}, 0, CURRENT_TIMESTAMP(6), #{code}, #{requestKey})
            ON DUPLICATE KEY UPDATE last_failure_at = VALUES(last_failure_at), last_failure_code = VALUES(last_failure_code),
                last_failure_request_key = VALUES(last_failure_request_key)
            """)
    void recordFailure(String sourceId, long warehouseId, String code, String requestKey);

    @Select("""
            SELECT w.source_id, w.warehouse_id, w.source_version, w.last_synced_at, w.last_failure_at,
                w.last_failure_code, w.last_failure_request_key,
                (SELECT COUNT(*) FROM inventory_sync_stock s
                 WHERE s.source_id = w.source_id AND s.warehouse_id = w.warehouse_id) sku_count
            FROM inventory_sync_warehouse w WHERE w.source_id = #{sourceId} AND w.warehouse_id = #{warehouseId}
            """)
    Status status(String sourceId, long warehouseId);
}

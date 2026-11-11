package com.taskflow.orders.inventory.mapper;

import java.time.Instant;
import java.util.List;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.Failure;
import com.taskflow.orders.inventory.mapper.InventoryMapper.Pending;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StockOutboxFailureMapper {
    record Delivery(long generation, String deliveryState) { }

    @Insert("""
            INSERT INTO stock_outbox_failure(event_id, generation, sku_id, attempts, detected_at, last_failed_at, error_code)
            VALUES (#{row.id}, #{row.generation}, #{row.skuId}, #{attempts}, #{detectedAt}, #{failedAt}, #{errorCode})
            """)
    void insert(Pending row, int attempts, Instant detectedAt, Instant failedAt, String errorCode);

    @Update("UPDATE stock_outbox_failure SET resolved_at = #{now} WHERE event_id = #{eventId} AND resolved_at IS NULL")
    void resolve(String eventId, Instant now);

    @Select("""
            <script>
            SELECT * FROM stock_outbox_failure WHERE id &gt; #{afterId}
            <if test="eventId != null">AND event_id = #{eventId}</if>
            <if test="!includeResolved">AND resolved_at IS NULL</if>
            ORDER BY id LIMIT #{limit}
            </script>
            """)
    List<Failure> select(String eventId, long afterId, int limit, boolean includeResolved);

    @Select("SELECT generation, delivery_state FROM stock_outbox WHERE id = #{eventId} FOR UPDATE")
    Delivery lockDelivery(String eventId);

    @Select("SELECT * FROM stock_outbox_failure WHERE event_id = #{eventId} AND generation = #{generation} FOR UPDATE")
    Failure lockFailure(String eventId, long generation);

    @Update("""
            UPDATE stock_outbox SET generation = generation + 1, attempts = 0, next_attempt_at = #{now},
                delivery_state = 'PENDING'
            WHERE id = #{eventId} AND generation = #{generation} AND delivery_state = 'EXHAUSTED'
            """)
    int requeue(String eventId, long generation, Instant now);

    @Update("UPDATE stock_outbox_failure SET retry_requested_at = #{now}, retry_reason = #{reason} WHERE id = #{id}")
    void saveRetry(long id, Instant now, String reason);

    @Select("""
            SELECT id FROM stock_outbox_failure WHERE resolved_at < #{cutoff}
            ORDER BY resolved_at, id LIMIT #{limit}
            """)
    List<Long> previewExpired(Instant cutoff, int limit);

    @Delete("""
            <script>
            DELETE FROM stock_outbox_failure WHERE resolved_at &lt; #{cutoff} AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    void deleteExpired(Instant cutoff, List<Long> ids);
}

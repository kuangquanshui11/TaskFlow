package com.taskflow.orders.cleanup.mapper;

import java.time.Instant;
import java.util.List;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Batch;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CleanupMapper {
    record Run(Instant cutoff, Integer retentionDays, Integer batchSize, Instant lastActivityAt, boolean expired) { }

    @Insert("""
            INSERT INTO expired_data_cleanup_run(request_key, cutoff, retention_days, batch_size, last_activity_at)
            VALUES (#{requestKey}, #{cutoff}, #{retentionDays}, #{batchSize}, #{now})
            ON DUPLICATE KEY UPDATE request_key = request_key
            """)
    void bind(String requestKey, Instant cutoff, int retentionDays, int batchSize, Instant now);

    @Select("""
            SELECT cutoff, retention_days, batch_size, last_activity_at, expired FROM expired_data_cleanup_run
            WHERE request_key = #{requestKey} FOR UPDATE
            """)
    Run lockRun(String requestKey);

    @Select("""
            SELECT id FROM stock_outbox_history WHERE published_at < #{cutoff}
            ORDER BY published_at, id LIMIT #{limit}
            """)
    List<String> preview(Instant cutoff, int limit);

    @Select("""
            SELECT id FROM stock_outbox_history WHERE published_at < #{cutoff}
            ORDER BY published_at, id LIMIT #{limit} FOR UPDATE
            """)
    List<String> lockExpired(Instant cutoff, int limit);

    @Delete("""
            <script>
            DELETE FROM stock_outbox_history WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int delete(List<String> ids);

    @Select("""
            SELECT r.cutoff, b.batch_number, b.deleted_count, b.has_more
            FROM expired_data_cleanup_batch b JOIN expired_data_cleanup_run r ON r.request_key = b.request_key
            WHERE b.request_key = #{requestKey} AND b.batch_number = #{batchNumber}
            """)
    Batch batch(String requestKey, int batchNumber);

    @Insert("""
            INSERT INTO expired_data_cleanup_batch(request_key, batch_number, deleted_count, has_more)
            VALUES (#{requestKey}, #{batch.batchNumber}, #{batch.deletedCount}, #{batch.hasMore})
            """)
    void save(String requestKey, Batch batch);

    @Update("UPDATE expired_data_cleanup_run SET last_activity_at = #{now} WHERE request_key = #{key}")
    void touch(String key, Instant now);

    @Select("""
            SELECT request_key FROM expired_data_cleanup_run WHERE expired = FALSE AND last_activity_at < #{cutoff}
            ORDER BY last_activity_at, request_key LIMIT #{limit}
            """)
    List<String> previewExpiredRuns(Instant cutoff, int limit);

    @Delete("DELETE FROM expired_data_cleanup_batch WHERE request_key = #{key}")
    void deleteReceipts(String key);

    @Update("""
            UPDATE expired_data_cleanup_run SET expired = TRUE, cutoff = NULL, retention_days = NULL,
                batch_size = NULL, last_activity_at = NULL WHERE request_key = #{key}
            """)
    void expireRun(String key);
}

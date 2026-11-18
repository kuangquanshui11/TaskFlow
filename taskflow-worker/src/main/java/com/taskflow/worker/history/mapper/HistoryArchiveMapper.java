package com.taskflow.worker.history.mapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryArchiveMapper {
    private final JdbcTemplate jdbc;

    public HistoryArchiveMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockBatch() {
        jdbc.queryForObject("SELECT id FROM history_archive_gate WHERE id=1 FOR UPDATE", Integer.class);
    }

    public Map<String, Integer> process(Instant cutoff, int limit, boolean preview) {
        var attempts = jdbc.queryForList("""
                SELECT e.attempt_id FROM worker_execution e
                WHERE e.state IN ('SUCCEEDED','FAILED') AND e.reported=TRUE
                  AND e.finished_at<? AND e.result_acknowledged_at<?
                  AND NOT EXISTS (SELECT 1 FROM worker_execution_detail_archive h WHERE h.attempt_id=e.attempt_id)
                ORDER BY e.result_acknowledged_at,e.attempt_id LIMIT ?
                """ + (preview ? "" : " FOR UPDATE"), String.class, Timestamp.from(cutoff), Timestamp.from(cutoff), limit);
        if (!preview) attempts.forEach(this::move);
        return Map.of("executions", attempts.size());
    }

    private void move(String attemptId) {
        // 重投仍与 Inbox 的原始主键及接收指纹比较。
        jdbc.update("""
                INSERT INTO worker_execution_detail_archive (attempt_id,dispatch_json,message,archived_at)
                SELECT attempt_id,dispatch_json,message,CURRENT_TIMESTAMP(3) FROM worker_execution WHERE attempt_id=?
                """, attemptId);
        jdbc.update("UPDATE worker_execution SET dispatch_json='',message=NULL WHERE attempt_id=?", attemptId);
        jdbc.update("""
                INSERT INTO worker_attempt_event_archive
                    (id,run_id,attempt_id,worker_id,event_type,occurred_at,report_number,state,result_code)
                SELECT id,run_id,attempt_id,worker_id,event_type,occurred_at,report_number,state,result_code
                FROM worker_attempt_event WHERE attempt_id=?
                """, attemptId);
        jdbc.update("""
                DELETE FROM worker_attempt_event WHERE attempt_id=? AND id IN
                    (SELECT id FROM worker_attempt_event_archive WHERE attempt_id=?)
                """, attemptId, attemptId);
    }
}

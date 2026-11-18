package com.taskflow.scheduler.history.mapper;

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
        var runs = jdbc.queryForList("""
                SELECT r.id FROM run_execution r
                WHERE r.finished_at<? AND r.state IN ('SUCCEEDED','FAILED','CANCELLED')
                  AND NOT EXISTS (SELECT 1 FROM run_snapshot_archive h WHERE h.run_id=r.id)
                  AND NOT EXISTS (SELECT 1 FROM job_execution_slot s WHERE s.run_id=r.id)
                  AND NOT EXISTS (SELECT 1 FROM run_attempt a WHERE a.run_id=r.id
                                  AND a.state NOT IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED'))
                  AND NOT EXISTS (SELECT 1 FROM run_cancellation c WHERE c.run_id=r.id
                                  AND c.outcome='REQUESTED')
                  AND NOT EXISTS (
                      SELECT 1 FROM run_outcome_case c WHERE c.run_id=r.id AND NOT EXISTS (
                          SELECT 1 FROM run_outcome_review v WHERE v.attempt_id=c.attempt_id
                          AND v.decision IN ('EFFECT_APPLIED','NO_EFFECT')))
                  AND NOT EXISTS (
                      SELECT 1 FROM run_attempt a JOIN dispatch_outbox o ON o.attempt_id=a.id
                      WHERE a.run_id=r.id AND (o.state IN ('WAITING','READY','SENDING')
                        OR (o.state='FAILED' AND NOT EXISTS (
                            SELECT 1 FROM dispatch_failure_disposition d WHERE d.source_kind='OUTBOX'
                            AND d.source_id=o.event_id AND d.action='RESOLVE'))))
                  AND NOT EXISTS (
                      SELECT 1 FROM dispatch_dead_letter l WHERE l.run_id=r.id AND NOT EXISTS (
                          SELECT 1 FROM dispatch_failure_disposition d WHERE d.source_kind='DEAD_LETTER'
                          AND d.source_id=l.record_id AND d.action='RESOLVE'))
                ORDER BY r.finished_at,r.id LIMIT ?
                """ + (preview ? "" : " FOR UPDATE"), String.class, Timestamp.from(cutoff), limit);
        if (!preview) runs.forEach(this::move);
        return Map.of("runs", runs.size());
    }

    private void move(String runId) {
        // Run 行继续承载两个唯一键及状态锁。细节与事件的迁移在同一事务内可见。
        jdbc.update("""
                INSERT INTO run_snapshot_archive (run_id,snapshot_json,archived_at)
                SELECT id,snapshot_json,CURRENT_TIMESTAMP(3) FROM run_execution WHERE id=?
                """, runId);
        jdbc.update("UPDATE run_execution SET snapshot_json='' WHERE id=?", runId);
        jdbc.update("""
                INSERT INTO run_attempt_detail_archive (attempt_id,message)
                SELECT id,message FROM run_attempt WHERE run_id=? AND message IS NOT NULL
                """, runId);
        jdbc.update("UPDATE run_attempt SET message=NULL WHERE run_id=? AND message IS NOT NULL", runId);
        jdbc.update("""
                INSERT INTO run_cancellation_detail_archive (run_id,reason)
                SELECT run_id,reason FROM run_cancellation WHERE run_id=?
                """, runId);
        jdbc.update("UPDATE run_cancellation SET reason='' WHERE run_id=?", runId);
        jdbc.update("""
                INSERT INTO run_outcome_review_detail_archive (review_id,evidence)
                SELECT v.review_id,v.evidence FROM run_outcome_review v JOIN run_outcome_case c
                  ON c.attempt_id=v.attempt_id WHERE c.run_id=?
                """, runId);
        jdbc.update("""
                UPDATE run_outcome_review SET evidence='' WHERE attempt_id IN
                  (SELECT attempt_id FROM run_outcome_case WHERE run_id=?)
                """, runId);
        jdbc.update("""
                INSERT INTO dispatch_outbox_payload_archive (event_id,message_json)
                SELECT o.event_id,o.message_json FROM dispatch_outbox o JOIN run_attempt a ON a.id=o.attempt_id
                WHERE a.run_id=? AND o.message_json IS NOT NULL
                """, runId);
        jdbc.update("""
                UPDATE dispatch_outbox SET message_json=NULL WHERE message_json IS NOT NULL AND attempt_id IN
                  (SELECT id FROM run_attempt WHERE run_id=?)
                """, runId);
        jdbc.update("""
                INSERT INTO scheduler_attempt_event_archive
                    (id,run_id,attempt_id,worker_id,event_type,occurred_at,lease_version,sequence_number,state,result_code,deadline_at)
                SELECT id,run_id,attempt_id,worker_id,event_type,occurred_at,lease_version,sequence_number,state,result_code,deadline_at
                FROM scheduler_attempt_event WHERE run_id=?
                """, runId);
        // 按已复制的 ID 删除，即使有迟到的证据追加，也不会删除未复制的行。
        jdbc.update("""
                DELETE FROM scheduler_attempt_event WHERE run_id=? AND id IN
                    (SELECT id FROM scheduler_attempt_event_archive WHERE run_id=?)
                """, runId, runId);
    }
}

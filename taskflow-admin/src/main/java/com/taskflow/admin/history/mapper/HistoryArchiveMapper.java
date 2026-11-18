package com.taskflow.admin.history.mapper;

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
        jdbc.queryForList("SELECT gate_name FROM config_reconciliation_gate WHERE gate_name='repair' FOR UPDATE");
    }

    public Map<String, Integer> process(Instant cutoff, int limit, boolean preview) {
        var before = Timestamp.from(cutoff);
        var lock = preview ? "" : " FOR UPDATE";
        var audits = jdbc.queryForList("""
                SELECT o.id FROM operation_audit o WHERE o.updated_at<?
                  AND (o.status='APPLIED' OR (o.status='REJECTED' AND o.request_key IS NULL))
                  AND NOT EXISTS (SELECT 1 FROM operation_audit_archive h WHERE h.id=o.id)
                ORDER BY o.updated_at,o.id LIMIT ?
                """ + lock, String.class, before, limit);
        var scans = jdbc.queryForList("""
                SELECT s.request_id FROM config_reconciliation_scan s WHERE s.state='COMPLETED' AND s.finished_at<?
                  AND EXISTS (
                    SELECT 1 FROM config_reconciliation_check c WHERE c.scan_id=s.request_id
                    AND NOT EXISTS (SELECT 1 FROM reconciliation_check_archive h WHERE h.check_id=c.check_id)
                    AND (c.round_id IS NULL OR EXISTS (
                        SELECT 1 FROM config_reconciliation_round r WHERE r.round_id=c.round_id AND r.phase='COMPLETED')))
                ORDER BY s.finished_at,s.request_id LIMIT ?
                """ + lock, String.class, before, limit);
        var sync = jdbc.queryForList("""
                SELECT o.event_id FROM job_sync_outbox o WHERE o.updated_at<?
                  AND o.state IN ('SYNCED','SUPERSEDED') AND o.deleted=FALSE
                  AND EXISTS (SELECT 1 FROM job_definition j WHERE j.id=o.job_id AND j.config_version>o.config_version)
                  AND NOT EXISTS (SELECT 1 FROM job_sync_payload_archive h WHERE h.event_id=o.event_id)
                ORDER BY o.updated_at,o.event_id LIMIT ?
                """ + lock, String.class, before, limit);
        if (!preview) {
            audits.forEach(this::moveAudit);
            scans.forEach(this::moveScan);
            sync.forEach(this::moveSync);
        }
        return Map.of("audits", audits.size(), "reconciliationScans", scans.size(), "syncEvents", sync.size());
    }

    private void moveAudit(String id) {
        jdbc.update("""
                INSERT INTO operation_audit_archive
                    (id,actor,action,job_id,config_version,request_key,run_id,status,result_code,
                     request_id,trace_id,occurred_at,updated_at,check_count)
                SELECT id,actor,action,job_id,config_version,request_key,run_id,status,result_code,
                       request_id,trace_id,occurred_at,updated_at,check_count
                FROM operation_audit WHERE id=?
                """, id);
        jdbc.update("DELETE FROM operation_audit WHERE id=? AND request_key IS NULL", id);
        // 手动触发的回执保留原请求键、操作者和 Run 绑定。
        jdbc.update("UPDATE operation_audit SET request_id=NULL,trace_id=NULL WHERE id=?", id);
    }

    private void moveScan(String id) {
        jdbc.update("""
                INSERT INTO reconciliation_check_archive (check_id,record_json)
                SELECT c.check_id,c.record_json FROM config_reconciliation_check c
                WHERE c.scan_id=? AND c.record_json<>'' AND (c.round_id IS NULL OR EXISTS (
                    SELECT 1 FROM config_reconciliation_round r WHERE r.round_id=c.round_id AND r.phase='COMPLETED'))
                """, id);
        jdbc.update("""
                UPDATE config_reconciliation_check SET record_json='' WHERE scan_id=? AND check_id IN
                    (SELECT check_id FROM reconciliation_check_archive)
                """, id);
    }

    private void moveSync(String id) {
        // 保留版本和事件 ID，以及所有现行配置和删除墓碑的原始快照。
        jdbc.update("""
                INSERT INTO job_sync_payload_archive (event_id,snapshot_json)
                SELECT event_id,snapshot_json FROM job_sync_outbox WHERE event_id=?
                """, id);
        jdbc.update("UPDATE job_sync_outbox SET snapshot_json='' WHERE event_id=?", id);
    }
}

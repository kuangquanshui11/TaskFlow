package com.taskflow.admin.reconciliation.mapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ReconciliationMapper {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public ReconciliationMapper(JdbcTemplate jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    public Instant now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class).toInstant(); }
    public Instant lockGate(String name) {
        return jdbc.queryForObject("SELECT next_allowed_at FROM config_reconciliation_gate WHERE gate_name=? FOR UPDATE", Timestamp.class, name).toInstant();
    }
    public void gate(String name, Instant until) {
        jdbc.update("UPDATE config_reconciliation_gate SET next_allowed_at=? WHERE gate_name=?", ts(until), name);
    }
    public List<Source> sources(long afterId, int limit) {
        // 包含软删除记录，只读取本服务权威配置与对应版本的发送意图。
        return jdbc.query("""
                SELECT j.id,j.config_version,j.deleted_at,j.enabled,o.event_id
                FROM job_definition j LEFT JOIN job_sync_outbox o ON o.job_id=j.id AND o.config_version=j.config_version
                WHERE j.id>? ORDER BY j.id LIMIT ?
                """, (rs, index) -> new Source(rs.getLong("id"), rs.getLong("config_version"), rs.getTimestamp("deleted_at") != null,
                rs.getTimestamp("deleted_at") == null && rs.getBoolean("enabled"), uuid(rs.getString("event_id"))), afterId, limit);
    }
    public Optional<Source> lockSource(long jobId) {
        // 使用当前读，避免 MySQL REPEATABLE READ 中较早读取明细建立的快照掩盖刚提交的配置变更。
        return jdbc.query("""
                SELECT j.id,j.config_version,j.deleted_at,j.enabled,o.event_id
                FROM job_definition j LEFT JOIN job_sync_outbox o ON o.job_id=j.id AND o.config_version=j.config_version
                WHERE j.id=? FOR UPDATE
                """, (rs, index) -> new Source(rs.getLong("id"), rs.getLong("config_version"), rs.getTimestamp("deleted_at") != null,
                rs.getTimestamp("deleted_at") == null && rs.getBoolean("enabled"), uuid(rs.getString("event_id"))), jobId).stream().findFirst();
    }
    public Optional<Scan> scan(UUID id) {
        return jdbc.query("SELECT * FROM config_reconciliation_scan WHERE request_id=?", (rs, index) -> new Scan(id,
                rs.getLong("after_job_id"), rs.getInt("batch_size"), rs.getLong("next_after_job_id"), rs.getBoolean("has_more"),
                rs.getString("state"), rs.getString("actor"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("lease_until").toInstant(), rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                checks(id)), id.toString()).stream().findFirst();
    }
    public List<Check> checks(UUID scanId) {
        return jdbc.query("SELECT record_json,repair_request_id FROM config_reconciliation_check WHERE scan_id=? ORDER BY job_id",
                (rs, index) -> {
                    var check = json.readValue(rs.getString("record_json"), Check.class);
                    return new Check(check.id(), check.scanId(), check.source(), check.replica(), check.difference(), check.errorCode(),
                            check.checkedAt(), uuid(rs.getString("repair_request_id")));
                }, scanId.toString());
    }
    public Optional<Check> check(UUID id) {
        return jdbc.query("SELECT scan_id FROM config_reconciliation_check WHERE check_id=?", (rs, index) -> UUID.fromString(rs.getString(1)),
                id.toString()).stream().findFirst().flatMap(scanId -> checks(scanId).stream().filter(check -> check.id().equals(id)).findFirst());
    }
    public void insertScan(Scan scan) {
        jdbc.update("""
                INSERT INTO config_reconciliation_scan
                (request_id,after_job_id,batch_size,next_after_job_id,has_more,state,actor,started_at,lease_until)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, scan.requestId().toString(), scan.afterJobId(), scan.batchSize(), scan.nextAfterJobId(), scan.hasMore(), scan.state(),
                scan.actor(), ts(scan.startedAt()), ts(scan.leaseUntil()));
    }
    public void complete(UUID scanId, List<Check> checks, Instant now) {
        // 过期扫描不能提交结果或释放新扫描的限速窗口。
        int changed = jdbc.update("UPDATE config_reconciliation_scan SET state='COMPLETED',finished_at=? WHERE request_id=? AND state='RUNNING' AND lease_until>?",
                ts(now), scanId.toString(), ts(now));
        if (changed != 1) return;
        for (var check : checks) jdbc.update("INSERT INTO config_reconciliation_check (check_id,scan_id,job_id,record_json) VALUES (?,?,?,?)",
                check.id().toString(), scanId.toString(), check.source().jobId(), json.writeValueAsString(check));
    }
    public Optional<Repair> repair(UUID requestId) {
        return jdbc.query("SELECT * FROM config_reconciliation_repair WHERE request_id=?", (rs, index) -> new Repair(requestId,
                UUID.fromString(rs.getString("check_id")), uuid(rs.getString("event_id")), rs.getString("outcome"), rs.getString("actor"),
                rs.getTimestamp("created_at").toInstant()), requestId.toString()).stream().findFirst();
    }
    public void insertRepair(Repair repair, boolean consumed) {
        jdbc.update("INSERT INTO config_reconciliation_repair (request_id,check_id,event_id,outcome,actor,created_at) VALUES (?,?,?,?,?,?)",
                repair.requestId().toString(), repair.checkId().toString(), string(repair.eventId()), repair.outcome(), repair.actor(), ts(repair.createdAt()));
        if (consumed) jdbc.update("UPDATE config_reconciliation_check SET repair_request_id=? WHERE check_id=? AND repair_request_id IS NULL",
                repair.requestId().toString(), repair.checkId().toString());
    }
    public boolean requeue(UUID eventId, Instant now) {
        return jdbc.update("""
                UPDATE job_sync_outbox SET state='PENDING',attempts=0,next_attempt_at=?,lease_token=NULL,lease_until=NULL,
                    last_error_code=NULL,acknowledged_version=NULL,synced_at=NULL,updated_at=?
                WHERE event_id=? AND state IN ('SYNCED','SUPERSEDED','FAILED')
                """, ts(now), ts(now), eventId.toString()) == 1;
    }
    private static String string(UUID id) { return id == null ? null : id.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}

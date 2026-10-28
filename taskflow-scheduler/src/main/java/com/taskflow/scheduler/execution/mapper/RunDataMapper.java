package com.taskflow.scheduler.execution.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.taskflow.scheduler.execution.model.RunRecords.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RunDataMapper {
    private final JdbcTemplate jdbc;
    public RunDataMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void insert(RunRow row) {
        jdbc.update("INSERT INTO run_execution (id, job_id, config_version, request_key, fingerprint, state, snapshot_json, current_attempt, created_at, updated_at, trigger_type, scheduled_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id().toString(), row.jobId(), row.configVersion(), row.requestKey().toString(), row.fingerprint(),
                row.state(), row.snapshotJson(), row.currentAttempt(), ts(row.createdAt()), ts(row.updatedAt()), row.triggerType(), ts(row.scheduledAt()));
    }
    public void insertAttempt(Attempt row) {
        jdbc.update("INSERT INTO run_attempt (id,run_id,attempt_number,lease_version,state,dispatch_count,next_dispatch_at) VALUES (?,?,?,?,?,?,?)",
                row.id().toString(), row.runId().toString(), row.attemptNumber(), row.leaseVersion(),
                row.state(), row.dispatchCount(), ts(row.nextDispatchAt()));
    }
    public Optional<RunRow> find(UUID id) {
        return jdbc.query("SELECT * FROM run_execution WHERE id=?", this::readRun, id.toString()).stream().findFirst();
    }
    public Optional<RunRow> lock(UUID id) {
        return jdbc.query("SELECT * FROM run_execution WHERE id=? FOR UPDATE", this::readRun, id.toString()).stream().findFirst();
    }
    public Optional<RunRow> byKey(UUID key) {
        return jdbc.query("SELECT * FROM run_execution WHERE request_key=?", this::readRun, key.toString()).stream().findFirst();
    }
    public Optional<RunRow> bySchedule(long jobId, Instant scheduledAt) {
        return jdbc.query("SELECT * FROM run_execution WHERE job_id=? AND scheduled_at=?", this::readRun,
                jobId, ts(scheduledAt)).stream().findFirst();
    }
    public Optional<Attempt> attempt(UUID id) {
        return jdbc.query("SELECT * FROM run_attempt WHERE id=?", this::readAttempt, id.toString()).stream().findFirst();
    }
    public Optional<Attempt> lockAttempt(UUID id) {
        return jdbc.query("SELECT * FROM run_attempt WHERE id=? FOR UPDATE", this::readAttempt, id.toString()).stream().findFirst();
    }
    public List<Attempt> attempts(UUID id) {
        return jdbc.query("SELECT * FROM run_attempt WHERE run_id=? ORDER BY attempt_number", this::readAttempt, id.toString());
    }
    public List<RunRow> select(long jobId, int page, int size) {
        return jdbc.query("SELECT * FROM run_execution WHERE job_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::readRun, jobId, size, (long) (page - 1) * size);
    }
    public long count(long jobId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM run_execution WHERE job_id=?", Long.class, jobId);
    }
    public List<UUID> due(Instant now) {
        return jdbc.query("SELECT id FROM run_attempt WHERE state IN ('READY','DISPATCHING') AND next_dispatch_at<=? ORDER BY next_dispatch_at,id LIMIT 100",
                (rs, index) -> UUID.fromString(rs.getString(1)), ts(now));
    }
    public List<UUID> expired(Instant now) {
        return jdbc.query("SELECT id FROM run_attempt WHERE state IN ('READY','DISPATCHING','RUNNING') AND expires_at IS NOT NULL AND (expires_at<=? OR lease_until<=?) LIMIT 100",
                (rs, index) -> UUID.fromString(rs.getString(1)), ts(now), ts(now));
    }
    public List<UUID> mqDue(Instant now) {
        return jdbc.query("SELECT a.id FROM run_attempt a JOIN dispatch_outbox o ON o.attempt_id=a.id WHERE a.state IN ('READY','DISPATCHING') AND a.next_dispatch_at<=? AND o.state IN ('WAITING','PUBLISHED','FAILED') ORDER BY a.next_dispatch_at,a.id LIMIT 100",
                (rs, index) -> UUID.fromString(rs.getString(1)), ts(now));
    }
    public void dispatching(UUID id, Instant expires, Instant next, Instant now) {
        jdbc.update("UPDATE run_attempt SET state='DISPATCHING', dispatch_count=dispatch_count+1, expires_at=?, next_dispatch_at=?, started_at=COALESCE(started_at,?) WHERE id=? AND state IN ('READY','DISPATCHING')",
                ts(expires), ts(next), ts(now), id.toString());
    }
    public boolean accepted(Attempt expected, Instant now) {
        return jdbc.update("UPDATE run_attempt SET state='RUNNING' WHERE id=? AND run_id=? AND attempt_number=? AND lease_version=? AND state IN ('READY','DISPATCHING') AND expires_at>? AND (lease_until IS NULL OR lease_until>?)",
                expected.id().toString(), expected.runId().toString(), expected.attemptNumber(), expected.leaseVersion(), ts(now), ts(now)) == 1;
    }
    public boolean updateLease(Attempt expected, String workerId, UUID sessionId, Instant until, Instant now) {
        return jdbc.update("UPDATE run_attempt SET lease_until=?,owner_worker_id=?,owner_session_id=? WHERE id=? AND run_id=? AND lease_version=? AND state=? AND state IN ('READY','DISPATCHING','RUNNING') AND expires_at>? AND expires_at>=? AND (lease_until IS NULL OR lease_until>?) AND (owner_worker_id IS NULL OR (owner_worker_id=? AND owner_session_id=?))",
                ts(until), workerId, sessionId.toString(), expected.id().toString(), expected.runId().toString(),
                expected.leaseVersion(), expected.state(), ts(now), ts(until), ts(now), workerId, sessionId.toString()) == 1;
    }
    public void ready(UUID id, Instant next) {
        jdbc.update("UPDATE run_attempt SET state='READY', next_dispatch_at=? WHERE id=? AND state='DISPATCHING'", ts(next), id.toString());
    }
    public boolean deferDispatch(Attempt expected, Instant next, Instant now) {
        return jdbc.update("UPDATE run_attempt SET next_dispatch_at=? WHERE id=? AND run_id=? AND lease_version=? AND state=? AND state IN ('READY','DISPATCHING') AND next_dispatch_at<=?",
                ts(next), expected.id().toString(), expected.runId().toString(), expected.leaseVersion(), expected.state(), ts(now)) == 1;
    }
    public boolean finish(Attempt expected, String state, String code, String message, Instant now) {
        return jdbc.update("UPDATE run_attempt SET state=?,result_code=?,message=?,finished_at=? WHERE id=? AND run_id=? AND attempt_number=? AND lease_version=? AND state=? AND state IN ('READY','DISPATCHING','RUNNING')",
                state, code, message, ts(now), expected.id().toString(), expected.runId().toString(), expected.attemptNumber(),
                expected.leaseVersion(), expected.state()) == 1;
    }
    public boolean runState(RunRow expected, String state, int attempt, String code, String message, Instant now) {
        return jdbc.update("UPDATE run_execution SET state=?,current_attempt=?,result_code=?,message=?,updated_at=? WHERE id=? AND current_attempt=? AND state=? AND state IN ('READY','RUNNING','RETRY_WAIT')",
                state, attempt, code, message, ts(now), expected.id().toString(), expected.currentAttempt(), expected.state()) == 1;
    }
    private RunRow readRun(ResultSet rs, int index) throws SQLException {
        return new RunRow(UUID.fromString(rs.getString("id")), rs.getLong("job_id"), rs.getLong("config_version"),
                UUID.fromString(rs.getString("request_key")), rs.getString("fingerprint"), rs.getString("state"),
                rs.getString("snapshot_json"), rs.getInt("current_attempt"), rs.getString("result_code"),
                rs.getString("message"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("trigger_type"), instant(rs, "scheduled_at"));
    }
    private Attempt readAttempt(ResultSet rs, int index) throws SQLException {
        return new Attempt(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("run_id")),
                rs.getInt("attempt_number"), rs.getLong("lease_version"), rs.getString("state"),
                rs.getInt("dispatch_count"), instant(rs, "next_dispatch_at"), instant(rs, "expires_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"), rs.getString("result_code"), rs.getString("message"),
                instant(rs, "lease_until"), rs.getString("owner_worker_id"),
                rs.getString("owner_session_id") == null ? null : UUID.fromString(rs.getString("owner_session_id")));
    }
    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    // 与 TIMESTAMP(3) 精度一致，避免数据库向上舍入把“立即可派发”写成未来时间。
    private Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); }
}

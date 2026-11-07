package com.taskflow.scheduler.execution.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.taskflow.scheduler.execution.dto.AttemptEvents.SchedulerEvent;
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
    public List<SchedulerEvent> selectAttemptEvents(UUID runId, UUID attemptId, int limit) {
        return jdbc.query("""
                SELECT id,worker_id,event_type,occurred_at,lease_version,sequence_number,state,result_code,deadline_at
                FROM scheduler_attempt_event WHERE run_id=? AND attempt_id=? ORDER BY id DESC LIMIT ?
                """, (rs, i) -> new SchedulerEvent(rs.getLong("id"), rs.getString("worker_id"),
                rs.getString("event_type"), instant(rs, "occurred_at"), rs.getLong("lease_version"),
                rs.getObject("sequence_number", Integer.class), rs.getString("state"), rs.getString("result_code"),
                instant(rs, "deadline_at")), runId.toString(), attemptId.toString(), limit);
    }
    public List<RunRow> select(long jobId, int page, int size) {
        return jdbc.query("SELECT * FROM run_execution WHERE job_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::readRun, jobId, size, (long) (page - 1) * size);
    }
    public long count(long jobId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM run_execution WHERE job_id=?", Long.class, jobId);
    }
    public List<Summary> selectGlobal(com.taskflow.scheduler.execution.model.RunFilter filter, int page, int size) {
        var args = new java.util.ArrayList<Object>();
        String where = globalWhere(filter, args);
        args.add(size); args.add((long) (page - 1) * size);
        // 列表不读取配置参数，也不逐条查询 Attempt 或其他服务的任务表。
        return jdbc.query("SELECT id,job_id,config_version,state,current_attempt,result_code,message,created_at,updated_at,trigger_type,scheduled_at FROM run_execution"
                + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, index) -> new Summary(UUID.fromString(rs.getString("id")), rs.getLong("job_id"), rs.getLong("config_version"),
                        rs.getString("state"), rs.getInt("current_attempt"), rs.getString("result_code"), rs.getString("message"),
                        instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("trigger_type"), instant(rs, "scheduled_at")), args.toArray());
    }
    public long countGlobal(com.taskflow.scheduler.execution.model.RunFilter filter) {
        var args = new java.util.ArrayList<Object>();
        String where = globalWhere(filter, args);
        return jdbc.queryForObject("SELECT COUNT(*) FROM run_execution" + where, Long.class, args.toArray());
    }
    private String globalWhere(com.taskflow.scheduler.execution.model.RunFilter filter, List<Object> args) {
        var where = new StringBuilder(" WHERE 1=1");
        if (filter.from() != null) { where.append(" AND created_at>=? AND created_at<?"); args.add(windowTs(filter.from())); args.add(windowTs(filter.to())); }
        if (filter.jobId() != null) { where.append(" AND job_id=?"); args.add(filter.jobId()); }
        if (filter.state() != null) { where.append(" AND state=?"); args.add(filter.state()); }
        if (filter.triggerType() != null) { where.append(" AND trigger_type=?"); args.add(filter.triggerType()); }
        if (filter.runId() != null) { where.append(" AND id=?"); args.add(filter.runId().toString()); }
        return where.toString();
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
    public void bindTarget(UUID id, String workerId) {
        if (jdbc.update("UPDATE run_attempt SET target_worker_id=? WHERE id=? AND target_worker_id IS NULL AND expires_at IS NULL AND state='READY'",
                workerId, id.toString()) != 1) throw new IllegalStateException("Attempt 已绑定目标或已派发");
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
    public void appendAttemptEvent(UUID runId, UUID attemptId, String workerId, long leaseVersion,
            String type, Instant now, Integer sequence, String state, String code, Instant deadline) {
        jdbc.update("""
                INSERT INTO scheduler_attempt_event
                    (run_id,attempt_id,worker_id,lease_version,event_type,occurred_at,sequence_number,state,result_code,deadline_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, runId.toString(), attemptId.toString(), workerId, leaseVersion,
                type, ts(now), sequence, state, code, ts(deadline));
    }
    public boolean runState(RunRow expected, String state, int attempt, String code, String message, Instant now) {
        return jdbc.update("UPDATE run_execution SET state=?,current_attempt=?,result_code=?,message=?,updated_at=?,started_at=COALESCE(started_at,?),finished_at=COALESCE(finished_at,?) WHERE id=? AND current_attempt=? AND state=? AND state IN ('READY','RUNNING','RETRY_WAIT')",
                state, attempt, code, message, ts(now), ts("RUNNING".equals(state) ? now : null),
                ts(java.util.Set.of("SUCCEEDED", "FAILED").contains(state) ? now : null),
                expected.id().toString(), expected.currentAttempt(), expected.state()) == 1;
    }
    public List<StateCount> summaryStates(Instant from, Instant to) {
        return jdbc.query("SELECT state,COUNT(*) AS n,COUNT(CASE WHEN state IN ('SUCCEEDED','FAILED') AND started_at IS NOT NULL THEN finished_at END) AS timed_count,SUM(CASE WHEN state IN ('SUCCEEDED','FAILED') THEN TIMESTAMPDIFF(MICROSECOND,started_at,finished_at)/1000.0 END) AS total_millis FROM run_execution WHERE created_at>=? AND created_at<? GROUP BY state",
                (rs, i) -> new StateCount(rs.getString("state"), rs.getLong("n"), rs.getLong("timed_count"), rs.getBigDecimal("total_millis")), windowTs(from), windowTs(to));
    }
    public AttemptTotals summaryAttempts(Instant from, Instant to) {
        return jdbc.queryForObject("SELECT COUNT(*) AS n,COUNT(DISTINCT CASE WHEN a.attempt_number>1 THEN a.run_id END) AS retried FROM run_attempt a JOIN run_execution r ON r.id=a.run_id WHERE r.created_at>=? AND r.created_at<?",
                (rs, i) -> new AttemptTotals(rs.getLong("n"), rs.getLong("retried")), windowTs(from), windowTs(to));
    }
    public List<Long> summaryLatencies(Instant from, Instant to) {
        return jdbc.query("SELECT started_at,finished_at FROM run_execution WHERE created_at>=? AND created_at<? AND state IN ('SUCCEEDED','FAILED') AND started_at IS NOT NULL AND finished_at IS NOT NULL ORDER BY created_at,id LIMIT 10000",
                (rs, i) -> java.time.Duration.between(instant(rs, "started_at"), instant(rs, "finished_at")).toMillis(), windowTs(from), windowTs(to));
    }
    public List<FailureGroup> summaryFailures(Instant from, Instant to) {
        return jdbc.query("SELECT job_id,COALESCE(NULLIF(result_code,''),'UNKNOWN') AS code,COUNT(*) AS n,MAX(finished_at) AS last_failed_at FROM run_execution WHERE created_at>=? AND created_at<? AND state='FAILED' GROUP BY job_id,COALESCE(NULLIF(result_code,''),'UNKNOWN') ORDER BY n DESC,job_id,code LIMIT 5",
                (rs, i) -> new FailureGroup(rs.getLong("job_id"), rs.getString("code"), rs.getLong("n"), instant(rs, "last_failed_at")), windowTs(from), windowTs(to));
    }
    // 存储精度为毫秒；半开窗口的两端均向上取整，保留亚毫秒输入的包含/排除语义。
    private Timestamp windowTs(Instant value) {
        var millis = value.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return Timestamp.from(value.equals(millis) ? millis : millis.plusMillis(1));
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
                rs.getString("owner_session_id") == null ? null : UUID.fromString(rs.getString("owner_session_id")), rs.getString("target_worker_id"));
    }
    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    // 与 TIMESTAMP(3) 精度一致，避免数据库向上舍入把“立即可派发”写成未来时间。
    private Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); }
    public long countDispatchOverdue(Instant now) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt WHERE state IN ('READY','DISPATCHING') AND next_dispatch_at<=?", Long.class, ts(now));
    }
    // 观测契约使用 expires_at；执行短租约 lease_until 的业务收尾规则保持独立。
    public long countLeaseExpired(Instant now) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt WHERE state IN ('READY','DISPATCHING','RUNNING') AND expires_at<=?", Long.class, ts(now));
    }
    public long countLeaseExpiring(Instant now) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt WHERE state IN ('READY','DISPATCHING','RUNNING') AND expires_at>? AND expires_at<=?", Long.class, ts(now), ts(now.plusSeconds(60)));
    }

}

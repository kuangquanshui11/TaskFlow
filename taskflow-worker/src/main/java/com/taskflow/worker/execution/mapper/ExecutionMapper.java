package com.taskflow.worker.execution.mapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import com.taskflow.worker.execution.model.ExecutionRow;
import com.taskflow.worker.execution.model.ExecutionOwner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExecutionMapper {
    private final JdbcTemplate jdbc;
    public ExecutionMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<ExecutionRow> find(UUID id) {
        return jdbc.query("SELECT * FROM worker_execution WHERE attempt_id=?", this::read, id.toString()).stream().findFirst();
    }
    public void insert(UUID attemptId, UUID runId, String fingerprint, String encoded, Instant now) {
        jdbc.update("INSERT INTO worker_execution (attempt_id,run_id,fingerprint,dispatch_json,state,retryable,reported,report_count,next_report_at,created_at) VALUES (?,?,?,?,'ACCEPTED',FALSE,FALSE,0,?,?)",
                attemptId.toString(), runId.toString(), fingerprint, encoded, ts(now), ts(now));
    }
    public long pendingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM worker_execution WHERE state IN ('ACCEPTED','RUNNING')", Long.class);
    }
    public com.taskflow.worker.execution.model.ExecutionLoad selectLoad() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN state='RUNNING' THEN 1 ELSE 0 END),0) running_count,COALESCE(SUM(CASE WHEN state='ACCEPTED' THEN 1 ELSE 0 END),0) pending_count FROM worker_execution WHERE state IN ('ACCEPTED','RUNNING')",
                (rs,i)->new com.taskflow.worker.execution.model.ExecutionLoad(rs.getInt("running_count"),rs.getInt("pending_count")));
    }
    public List<ExecutionRow> pending(int limit) {
        return jdbc.query("SELECT * FROM worker_execution WHERE state='ACCEPTED' ORDER BY created_at LIMIT ?", this::read, limit);
    }
    public boolean claim(UUID id, ExecutionOwner identity) {
        return jdbc.update("UPDATE worker_execution SET state='RUNNING',report_worker_id=?,report_session_id=? WHERE attempt_id=? AND state='ACCEPTED'",
                identity.workerId(), identity.sessionId().toString(), id.toString()) == 1;
    }
    public boolean finish(UUID id, String state, String code, String message, boolean retryable, Instant now) {
        return jdbc.update("UPDATE worker_execution SET state=?,result_code=?,message=?,retryable=?,finished_at=?,next_report_at=? WHERE attempt_id=? AND state='RUNNING'",
                state, code, message, retryable, ts(now), ts(now), id.toString()) == 1;
    }
    public void recoverRunning(Instant now) {
        jdbc.update("UPDATE worker_execution SET state='FAILED',result_code='WORKER_RESTARTED',message='Worker 重启，之前执行的外部副作用未知',retryable=FALSE,finished_at=?,next_report_at=? WHERE state='RUNNING'", ts(now), ts(now));
    }
    public List<ExecutionRow> reports(Instant now, int maxAttempts) {
        return jdbc.query("SELECT * FROM worker_execution WHERE state IN ('SUCCEEDED','FAILED') AND reported=FALSE AND report_count<? AND next_report_at<=? ORDER BY next_report_at LIMIT 100",
                this::read, maxAttempts, ts(now));
    }
    public boolean reportClaim(UUID id, int previousCount, Instant next) {
        return jdbc.update("UPDATE worker_execution SET report_count=report_count+1,next_report_at=? WHERE attempt_id=? AND report_count=? AND reported=FALSE",
                ts(next), id.toString(), previousCount) == 1;
    }
    public void reported(UUID id) {
        jdbc.update("UPDATE worker_execution SET reported=TRUE WHERE attempt_id=?", id.toString());
    }
    private ExecutionRow read(ResultSet rs, int index) throws SQLException {
        return new ExecutionRow(UUID.fromString(rs.getString("attempt_id")), UUID.fromString(rs.getString("run_id")),
                rs.getString("fingerprint"), rs.getString("dispatch_json"), rs.getString("state"), rs.getString("result_code"),
                rs.getString("message"), rs.getBoolean("retryable"), rs.getBoolean("reported"), rs.getInt("report_count"),
                instant(rs, "next_report_at"), instant(rs, "created_at"), instant(rs, "finished_at"), rs.getString("report_worker_id"),
                rs.getString("report_session_id") == null ? null : UUID.fromString(rs.getString("report_session_id")));
    }
    private Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private Timestamp ts(Instant value) { return Timestamp.from(value); }
}

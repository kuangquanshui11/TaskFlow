package com.taskflow.admin.jobsync.mapper;

import com.taskflow.admin.observability.config.AsyncTracing.Context;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.taskflow.admin.jobsync.model.JobSyncRecords.*;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import com.taskflow.admin.job.exception.JobException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JobSyncMapper {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JobSyncMapper(JdbcTemplate jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    public void insert(Event event, Context traceContext) {
        jdbc.update("INSERT INTO job_sync_outbox (event_id,job_id,config_version,deleted,snapshot_json,next_attempt_at,traceparent,tracestate) VALUES (?,?,?,?,?,?,?,?)",
                event.eventId().toString(), event.jobId(), event.configVersion(), event.deleted(),
                json.writeValueAsString(event.configuration()), ts(Instant.now()), traceContext.traceparent(), traceContext.tracestate());
    }

    public Optional<Status> latest(long jobId) {
        return jdbc.query("SELECT * FROM job_sync_outbox WHERE job_id=? ORDER BY config_version DESC LIMIT 1",
                this::readStatus, jobId).stream().findFirst();
    }

    public List<Event> recoveryEvents(long afterJobId, long upperJobId, int limit) {
        return jdbc.query("""
                SELECT j.id,j.config_version,j.deleted_at,j.enabled,o.event_id,o.deleted,o.snapshot_json
                FROM job_definition j LEFT JOIN job_sync_outbox o ON o.job_id=j.id AND o.config_version=j.config_version
                WHERE j.id>? AND j.id<=? ORDER BY j.id LIMIT ?
                """, (rs, index) -> {
            long jobId = rs.getLong("id");
            String eventId = rs.getString("event_id");
            boolean deleted = rs.getTimestamp("deleted_at") != null;
            if (eventId == null || deleted != rs.getBoolean("deleted"))
                throw new JobException(JobException.Kind.CONFLICT, "任务 #" + jobId + " 缺少匹配的权威 Outbox 事件");
            try {
                var configuration = json.readValue(rs.getString("snapshot_json"), Configuration.class);
                if (configuration == null || (!deleted && configuration.enabled() != rs.getBoolean("enabled")))
                    throw new IllegalArgumentException();
                return new Event(UUID.fromString(eventId), jobId, rs.getLong("config_version"), deleted, configuration);
            } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
                throw new JobException(JobException.Kind.CONFLICT, "任务 #" + jobId + " 的权威 Outbox 快照无效");
            }
        }, afterJobId, upperJobId, limit);
    }

    public List<UUID> due(Instant now, int limit) {
        return jdbc.query("""
                SELECT event_id FROM job_sync_outbox
                WHERE (state='PENDING' AND next_attempt_at<=?) OR (state='SENDING' AND lease_until<=?)
                ORDER BY next_attempt_at,created_at,event_id LIMIT ?
                """, (rs, index) -> UUID.fromString(rs.getString(1)), ts(now), ts(now), limit);
    }

    public Optional<Delivery> claim(UUID eventId, UUID token, Instant now, Instant until, int maxAttempts) {
        // 失联且耗尽次数的最后一次发送不能永远停在 SENDING，也不能绕过次数上限。
        jdbc.update("""
                UPDATE job_sync_outbox SET state='FAILED',lease_token=NULL,lease_until=NULL,
                    last_error_code='DELIVERY_UNCONFIRMED',updated_at=?
                WHERE event_id=? AND state='SENDING' AND lease_until<=? AND attempts>=?
                """, ts(now), eventId.toString(), ts(now), maxAttempts);
        int changed = jdbc.update("""
                UPDATE job_sync_outbox SET state='SENDING',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=?
                WHERE event_id=? AND attempts<? AND
                    ((state='PENDING' AND next_attempt_at<=?) OR (state='SENDING' AND lease_until<=?))
                """, token.toString(), ts(until), ts(now), eventId.toString(), maxAttempts, ts(now), ts(now));
        if (changed == 0) return Optional.empty();
        return jdbc.query("SELECT * FROM job_sync_outbox WHERE event_id=? AND lease_token=?", (rs, index) ->
                new Delivery(new Event(eventId, rs.getLong("job_id"), rs.getLong("config_version"),
                        rs.getBoolean("deleted"), json.readValue(rs.getString("snapshot_json"), Configuration.class)),
                        token, rs.getInt("attempts"), new Context(rs.getString("traceparent"), rs.getString("tracestate"))), eventId.toString(), token.toString()).stream().findFirst();
    }

    public void acknowledge(Delivery delivery, Acknowledgement ack, Instant now) {
        jdbc.update("""
                UPDATE job_sync_outbox SET state=?,acknowledged_version=?,synced_at=?,last_error_code=NULL,
                    lease_token=NULL,lease_until=NULL,updated_at=?
                WHERE event_id=? AND state='SENDING' AND lease_token=? AND lease_until>?
                """, ack.acceptedVersion() == delivery.event().configVersion() ? "SYNCED" : "SUPERSEDED",
                ack.acceptedVersion(), ts(now), ts(now), delivery.event().eventId().toString(),
                delivery.leaseToken().toString(), ts(now));
    }

    public void fail(Delivery delivery, String code, boolean retry, Instant next, Instant now) {
        jdbc.update("""
                UPDATE job_sync_outbox SET state=?,last_error_code=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,updated_at=?
                WHERE event_id=? AND state='SENDING' AND lease_token=? AND lease_until>?
                """, retry ? "PENDING" : "FAILED", code, ts(next), ts(now), delivery.event().eventId().toString(),
                delivery.leaseToken().toString(), ts(now));
    }

    public boolean retry(long jobId, long version) {
        return jdbc.update("""
                UPDATE job_sync_outbox SET state='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP(3),
                    last_error_code=NULL,updated_at=CURRENT_TIMESTAMP(3)
                WHERE job_id=? AND config_version=? AND state='FAILED'
                """, jobId, version) == 1;
    }

    public void lockJob(long jobId) {
        // 包含软删除记录，与配置写入串行化，避免人工重试时选中了刚被替代的版本。
        jdbc.queryForList("SELECT id FROM job_definition WHERE id=? FOR UPDATE", jobId);
    }

    private Status readStatus(ResultSet rs, int index) throws SQLException {
        return new Status(UUID.fromString(rs.getString("event_id")), rs.getLong("job_id"), rs.getLong("config_version"),
                rs.getBoolean("deleted"), rs.getString("state"), rs.getInt("attempts"), time(rs,"next_attempt_at"),
                time(rs,"lease_until"), rs.getString("last_error_code"), (Long) rs.getObject("acknowledged_version"),
                time(rs,"synced_at"), time(rs,"updated_at"));
    }

    private static Timestamp ts(Instant time) { return Timestamp.from(time.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); }
    private static Instant time(ResultSet rs, String field) throws SQLException {
        var value = rs.getTimestamp(field); return value == null ? null : value.toInstant();
    }
}

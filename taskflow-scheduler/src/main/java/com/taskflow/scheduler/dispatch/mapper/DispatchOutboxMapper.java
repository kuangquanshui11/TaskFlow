package com.taskflow.scheduler.dispatch.mapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.taskflow.scheduler.dispatch.model.OutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DispatchOutboxMapper {
    private final JdbcTemplate jdbc;
    public DispatchOutboxMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(UUID attemptId, Instant when) {
        jdbc.update("INSERT INTO dispatch_outbox (event_id,attempt_id,state,publish_attempts,next_publish_at,claim_version,created_at) VALUES (?,?,'WAITING',0,?,0,?)",
                attemptId.toString(), attemptId.toString(), ts(when), ts(Instant.now()));
    }
    public Optional<OutboxEvent> find(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM dispatch_outbox WHERE event_id=?" + (lock ? " FOR UPDATE" : ""), (rs, i) ->
                new OutboxEvent(UUID.fromString(rs.getString("event_id")), UUID.fromString(rs.getString("attempt_id")),
                        rs.getString("state"), rs.getString("message_json"), instant(rs.getTimestamp("expires_at")),
                        rs.getInt("publish_attempts"), instant(rs.getTimestamp("next_publish_at")), rs.getLong("claim_version"),
                        instant(rs.getTimestamp("claim_until")), rs.getString("last_error_code")), id.toString()).stream().findFirst();
    }
    public void prepare(UUID id, String json, Instant expiresAt, Instant now) {
        if (jdbc.update("UPDATE dispatch_outbox SET state='READY',message_json=?,expires_at=?,next_publish_at=? WHERE event_id=? AND state='WAITING'",
                json, ts(expiresAt), ts(now), id.toString()) != 1) throw new IllegalStateException("Outbox 已被准备或取消");
    }
    public List<UUID> due(Instant now) {
        return jdbc.query("SELECT event_id FROM dispatch_outbox WHERE next_publish_at<=? AND (state='READY' OR (state='SENDING' AND claim_until<=?)) ORDER BY next_publish_at,event_id LIMIT 100",
                (rs, i) -> UUID.fromString(rs.getString(1)), ts(now), ts(now));
    }
    public boolean deferPublication(OutboxEvent expected, Instant next, Instant now) {
        // 仅推迟检查；不延长旧发送租约，不改变 claimVersion，旧 Publisher 仍受原租约约束。
        return jdbc.update("UPDATE dispatch_outbox SET next_publish_at=? WHERE event_id=? AND state=? AND claim_version=? AND next_publish_at<=? AND (state='READY' OR (state='SENDING' AND claim_until<=?))",
                ts(next), expected.eventId().toString(), expected.state(), expected.claimVersion(), ts(now), ts(now)) == 1;
    }
    public void claim(UUID id, Instant until) {
        jdbc.update("UPDATE dispatch_outbox SET state='SENDING',publish_attempts=publish_attempts+1,claim_version=claim_version+1,claim_until=? WHERE event_id=?",
                ts(until), id.toString());
    }
    public boolean published(OutboxEvent event, Instant now) {
        return jdbc.update("UPDATE dispatch_outbox SET state='PUBLISHED',published_at=?,claim_until=NULL,last_error_code=NULL WHERE event_id=? AND state='SENDING' AND claim_version=? AND claim_until>?",
                ts(now), event.eventId().toString(), event.claimVersion(), ts(now)) == 1;
    }
    public void failed(OutboxEvent event, boolean exhausted, String code, Instant next, Instant now) {
        jdbc.update("UPDATE dispatch_outbox SET state=?,last_error_code=?,next_publish_at=?,claim_until=NULL WHERE event_id=? AND state='SENDING' AND claim_version=? AND claim_until>?",
                exhausted ? "FAILED" : "READY", code, ts(next), event.eventId().toString(), event.claimVersion(), ts(now));
    }
    public void cancel(UUID attemptId) {
        jdbc.update("UPDATE dispatch_outbox SET state='CANCELLED',claim_until=NULL WHERE attempt_id=? AND state IN ('WAITING','READY','SENDING')", attemptId.toString());
    }
    public void exhaust(UUID id) {
        jdbc.update("UPDATE dispatch_outbox SET state='FAILED',claim_until=NULL,last_error_code='MQ_PUBLISH_UNCONFIRMED' WHERE event_id=? AND state IN ('READY','SENDING')", id.toString());
    }
    private Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}

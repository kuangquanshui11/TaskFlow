package com.taskflow.scheduler.dispatch.mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.taskflow.scheduler.dispatch.dto.DispatchFailures.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DispatchFailureMapper {
    private final JdbcTemplate jdbc;

    public DispatchFailureMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(DeadLetter dead) {
        jdbc.update("""
                INSERT INTO dispatch_dead_letter(record_id,run_id,attempt_id,event_id,dead_queue,source_queue,reason,payload_hash,received_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE record_id=record_id
                """, dead.id(), id(dead.runId()), id(dead.attemptId()), id(dead.eventId()), dead.queue(),
                dead.sourceQueue(), dead.reason(), dead.payloadHash(), Timestamp.from(dead.receivedAt()));
    }

    public List<DeadLetter> recent(UUID attemptId, UUID eventId, int limit) {
        String where = eventId != null ? "WHERE event_id=? " : attemptId != null ? "WHERE attempt_id=? " : "";
        Object[] args = where.isEmpty() ? new Object[]{limit} : new Object[]{id(eventId != null ? eventId : attemptId), limit};
        return dead("SELECT * FROM dispatch_dead_letter " + where + "ORDER BY received_at DESC,record_id LIMIT ?", args);
    }

    public Optional<DeadLetter> find(String id) {
        return dead("SELECT * FROM dispatch_dead_letter WHERE record_id=?", id).stream().findFirst();
    }

    public Optional<Disposition> disposition(UUID key) {
        return dispositions("SELECT * FROM dispatch_failure_disposition WHERE request_key=?", key.toString()).stream().findFirst();
    }

    public List<Disposition> history(Source source, String id) {
        return dispositions("""
                SELECT * FROM dispatch_failure_disposition WHERE source_kind=? AND source_id=?
                ORDER BY action
                """, source.name(), id);
    }

    public void record(Disposition entry) {
        jdbc.update("""
                INSERT INTO dispatch_failure_disposition(request_key,source_kind,source_id,action,actor,conclusion,evidence,recorded_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, entry.requestKey().toString(), entry.source().name(), entry.id(), entry.action().name(),
                entry.actor(), entry.conclusion(), entry.evidence(), Timestamp.from(entry.recordedAt()));
    }

    public long countUnresolvedDeadLetters() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM dispatch_dead_letter d WHERE NOT EXISTS (
                    SELECT 1 FROM dispatch_failure_disposition p WHERE p.source_kind='DEAD_LETTER'
                    AND p.source_id=d.record_id AND p.action='RESOLVE')
                """, Long.class);
    }

    public long countUnresolvedOutbox() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM dispatch_outbox o WHERE o.state='FAILED' AND NOT EXISTS (
                    SELECT 1 FROM dispatch_failure_disposition p WHERE p.source_kind='OUTBOX'
                    AND p.source_id=o.event_id AND p.action='RESOLVE')
                """, Long.class);
    }

    private List<DeadLetter> dead(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> new DeadLetter(rs.getString("record_id"), uuid(rs.getString("run_id")),
                uuid(rs.getString("attempt_id")), uuid(rs.getString("event_id")), rs.getString("dead_queue"),
                rs.getString("source_queue"), rs.getString("reason"), rs.getString("payload_hash"),
                rs.getTimestamp("received_at").toInstant()), args);
    }

    private List<Disposition> dispositions(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> new Disposition(UUID.fromString(rs.getString("request_key")),
                Source.valueOf(rs.getString("source_kind")), rs.getString("source_id"), Action.valueOf(rs.getString("action")),
                rs.getString("actor"), rs.getString("conclusion"), rs.getString("evidence"),
                rs.getTimestamp("recorded_at").toInstant()), args);
    }

    private static UUID uuid(String id) { return id == null ? null : UUID.fromString(id); }
    private static String id(UUID id) { return id == null ? null : id.toString(); }
}

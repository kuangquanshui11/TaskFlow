package com.taskflow.scheduler.plan.mapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.taskflow.scheduler.plan.model.PlanRecords.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlanMapper {
    private final JdbcTemplate jdbc;
    public PlanMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Receipt> receipts(UUID eventId, long jobId, long version) {
        return jdbc.query("SELECT * FROM schedule_sync_inbox WHERE event_id=? OR (job_id=? AND config_version=?)",
                (rs, index) -> new Receipt(UUID.fromString(rs.getString("event_id")), rs.getLong("job_id"),
                        rs.getLong("config_version"), rs.getString("fingerprint")), eventId.toString(), jobId, version);
    }

    public void insertReceipt(Receipt receipt, Instant now) {
        jdbc.update("INSERT INTO schedule_sync_inbox (event_id,job_id,config_version,fingerprint,received_at) VALUES (?,?,?,?,?)",
                receipt.eventId().toString(), receipt.jobId(), receipt.configVersion(), receipt.fingerprint(), Timestamp.from(now));
    }

    public Optional<Plan> find(long jobId, boolean lock) {
        return jdbc.query("SELECT * FROM schedule_plan WHERE job_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, index) -> new Plan(rs.getLong("job_id"), rs.getLong("config_version"), rs.getBoolean("deleted"),
                        rs.getBoolean("enabled"), rs.getString("snapshot_json"), rs.getTimestamp("updated_at").toInstant(),
                        time(rs, "next_fire_time"), time(rs, "calculated_at"), CalculationState.valueOf(rs.getString("calculation_state"))),
                jobId).stream().findFirst();
    }

    public void insert(Plan plan) {
        jdbc.update("INSERT INTO schedule_plan (job_id,config_version,deleted,enabled,snapshot_json,updated_at,next_fire_time,calculated_at,calculation_state) VALUES (?,?,?,?,?,?,?,?,?)",
                plan.jobId(), plan.configVersion(), plan.deleted(), plan.enabled(), plan.snapshotJson(), Timestamp.from(plan.updatedAt()),
                ts(plan.nextFireTime()), ts(plan.calculatedAt()), plan.calculationState().name());
    }

    public void update(Plan plan) {
        jdbc.update("UPDATE schedule_plan SET config_version=?,deleted=?,enabled=?,snapshot_json=?,updated_at=?,next_fire_time=?,calculated_at=?,calculation_state=? WHERE job_id=? AND config_version<?",
                plan.configVersion(), plan.deleted(), plan.enabled(), plan.snapshotJson(), Timestamp.from(plan.updatedAt()),
                ts(plan.nextFireTime()), ts(plan.calculatedAt()), plan.calculationState().name(),
                plan.jobId(), plan.configVersion());
    }

    public List<Long> pending(int limit) {
        return jdbc.query("SELECT job_id FROM schedule_plan WHERE calculation_state='PENDING' ORDER BY job_id LIMIT ?",
                (rs, index) -> rs.getLong(1), limit);
    }

    public List<Long> due(Instant now, int limit) {
        return jdbc.query("""
                SELECT job_id FROM schedule_plan WHERE calculation_state='READY' AND enabled=TRUE AND deleted=FALSE
                    AND next_fire_time<=? ORDER BY next_fire_time,job_id LIMIT ?
                """, (rs, index) -> rs.getLong(1), ts(now), limit);
    }

    public void advance(Plan plan, Calculation next) {
        int updated = jdbc.update("""
                UPDATE schedule_plan SET next_fire_time=?,calculated_at=?,calculation_state=?
                WHERE job_id=? AND config_version=? AND calculation_state='READY' AND next_fire_time=?
                    AND enabled=TRUE AND deleted=FALSE
                """, ts(next.nextFireTime()), ts(next.calculatedAt()), next.state().name(),
                plan.jobId(), plan.configVersion(), ts(plan.nextFireTime()));
        if (updated != 1) throw new IllegalStateException("计划在触发事务内发生变化");
    }

    public void initialize(Plan plan, Calculation calculation) {
        jdbc.update("""
                UPDATE schedule_plan SET next_fire_time=?,calculated_at=?,calculation_state=?
                WHERE job_id=? AND config_version=? AND calculation_state='PENDING'
                """, ts(calculation.nextFireTime()), ts(calculation.calculatedAt()), calculation.state().name(), plan.jobId(), plan.configVersion());
    }

    private static Instant time(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        var timestamp = rs.getTimestamp(name); return timestamp == null ? null : timestamp.toInstant();
    }
    private static Timestamp ts(Instant time) { return time == null ? null : Timestamp.from(time); }
}

package com.taskflow.admin.audit.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.taskflow.admin.audit.model.OperationAudit;
import com.taskflow.admin.audit.model.OperationAudit.Action;
import com.taskflow.admin.audit.model.OperationAudit.Query;
import com.taskflow.admin.audit.model.OperationAudit.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OperationAuditMapper {
    private final JdbcClient jdbc;

    public OperationAuditMapper(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OperationAudit record, Instant nextCheckAt) {
        jdbc.sql("""
                INSERT INTO operation_audit (id,actor,action,job_id,config_version,request_key,run_id,status,
                    result_code,request_id,trace_id,occurred_at,updated_at,next_check_at)
                VALUES (:id,:actor,:action,:job,:version,:key,NULL,:status,:code,:requestId,:traceId,:now,:now,:next)
                """)
                .param("id", record.id().toString()).param("actor", record.actor()).param("action", record.action().name())
                .param("job", record.jobId()).param("version", record.configVersion()).param("key", text(record.requestKey()))
                .param("status", record.status().name()).param("code", record.resultCode())
                .param("requestId", record.requestId()).param("traceId", record.traceId())
                .param("now", timestamp(record.occurredAt())).param("next", timestamp(nextCheckAt)).update();
    }

    public Optional<OperationAudit> byRequestKey(UUID key) {
        return jdbc.sql("SELECT * FROM operation_audit_history WHERE request_key=:key")
                .param("key", key.toString()).query(this::read).optional();
    }

    public void resolve(UUID id, Status status, String code, UUID runId, Instant now) {
        jdbc.sql("""
                UPDATE operation_audit SET status=:status,result_code=:code,run_id=:run,updated_at=:now,next_check_at=NULL
                WHERE id=:id AND status<>'APPLIED' AND (status='PENDING' OR :status='APPLIED')
                """).param("id", id.toString()).param("status", status.name()).param("code", code)
                .param("run", text(runId)).param("now", timestamp(now)).update();
    }

    public List<OperationAudit> due(Instant now, int limit) {
        return jdbc.sql("""
                SELECT * FROM operation_audit WHERE status='PENDING' AND next_check_at<=:now
                ORDER BY next_check_at,id LIMIT :limit
                """).param("now", timestamp(now)).param("limit", limit).query(this::read).list();
    }

    public boolean claim(OperationAudit audit, Instant now, Instant next) {
        return jdbc.sql("""
                UPDATE operation_audit SET check_count=check_count+1,next_check_at=:next
                WHERE id=:id AND status='PENDING' AND check_count=:count AND next_check_at<=:now
                """).param("id", audit.id().toString()).param("count", audit.checkCount())
                .param("now", timestamp(now)).param("next", timestamp(next)).update() == 1;
    }

    public List<OperationAudit> select(Query query) {
        var sql = new StringBuilder("SELECT * FROM operation_audit_history WHERE 1=1");
        var params = new java.util.HashMap<String, Object>();
        filter(sql, params, "occurred_at", ">=", "from", timestamp(query.from()));
        filter(sql, params, "occurred_at", "<", "to", timestamp(query.to()));
        filter(sql, params, "actor", "=", "actor", query.actor());
        filter(sql, params, "action", "=", "action", query.action() == null ? null : query.action().name());
        filter(sql, params, "job_id", "=", "job", query.jobId());
        filter(sql, params, "run_id", "=", "run", text(query.runId()));
        filter(sql, params, "request_key", "=", "key", text(query.requestKey()));
        sql.append(" ORDER BY occurred_at DESC,id DESC LIMIT :limit OFFSET :offset");
        return jdbc.sql(sql.toString()).params(params).param("limit", query.pageSize() + 1)
                .param("offset", (long) (query.page() - 1) * query.pageSize()).query(this::read).list();
    }

    private static void filter(StringBuilder sql, java.util.Map<String, Object> params, String column,
            String operator, String parameter, Object value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append(operator).append(':').append(parameter);
        params.put(parameter, value);
    }

    private OperationAudit read(ResultSet rs, int row) throws SQLException {
        return new OperationAudit(UUID.fromString(rs.getString("id")), rs.getString("actor"),
                Action.valueOf(rs.getString("action")), rs.getObject("job_id", Long.class),
                rs.getObject("config_version", Long.class), uuid(rs.getString("request_key")),
                uuid(rs.getString("run_id")), Status.valueOf(rs.getString("status")), rs.getString("result_code"),
                rs.getString("request_id"), rs.getString("trace_id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getInt("check_count"));
    }

    private static String text(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}

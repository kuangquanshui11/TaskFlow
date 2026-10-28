package com.taskflow.scheduler.node.mapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import com.taskflow.scheduler.node.model.WorkerNode;
import com.taskflow.scheduler.node.dto.WorkerNodeProtocol.Summary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkerNodeMapper {
    private final JdbcTemplate jdbc;
    public WorkerNodeMapper(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<WorkerNode> find(String id, boolean lock) {
        return jdbc.query("SELECT * FROM worker_node WHERE worker_id=?"+(lock?" FOR UPDATE":""),this::read,id).stream().findFirst();
    }
    public boolean sessionExists(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM worker_node_session WHERE session_id=?",Long.class,id.toString())>0;
    }
    public void save(WorkerNode node, boolean insert) {
        if (insert) jdbc.update("INSERT INTO worker_node (worker_id,display_name,session_id,generation,registration_fingerprint,handler_keys_json,parallelism,max_pending,running_count,pending_count,dispatch_transport,dispatch_vhost,dispatch_exchange,dispatch_queue,dispatch_routing_key,last_sequence,registered_at,last_heartbeat_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                node.workerId(),node.displayName(),node.sessionId().toString(),node.generation(),node.registrationFingerprint(),node.handlerKeysJson(),node.parallelism(),node.maxPending(),0,0,
                node.dispatchTransport(),node.dispatchVhost(),node.dispatchExchange(),node.dispatchQueue(),node.dispatchRoutingKey(),0,ts(node.registeredAt()),ts(node.lastHeartbeatAt()),ts(node.expiresAt()));
        else jdbc.update("UPDATE worker_node SET display_name=?,session_id=?,generation=?,registration_fingerprint=?,handler_keys_json=?,parallelism=?,max_pending=?,running_count=0,pending_count=0,dispatch_transport=?,dispatch_vhost=?,dispatch_exchange=?,dispatch_queue=?,dispatch_routing_key=?,last_sequence=0,registered_at=?,last_heartbeat_at=?,expires_at=? WHERE worker_id=?",
                node.displayName(),node.sessionId().toString(),node.generation(),node.registrationFingerprint(),node.handlerKeysJson(),node.parallelism(),node.maxPending(),node.dispatchTransport(),
                node.dispatchVhost(),node.dispatchExchange(),node.dispatchQueue(),node.dispatchRoutingKey(),ts(node.registeredAt()),ts(node.lastHeartbeatAt()),ts(node.expiresAt()),node.workerId());
        jdbc.update("INSERT INTO worker_node_session (session_id,worker_id,generation,registered_at) VALUES (?,?,?,?)",
                node.sessionId().toString(),node.workerId(),node.generation(),ts(node.registeredAt()));
    }
    public void heartbeat(String id, long sequence, int running, int pending, Instant now, Instant expires) {
        jdbc.update("UPDATE worker_node SET last_sequence=?,running_count=?,pending_count=?,last_heartbeat_at=?,expires_at=? WHERE worker_id=?",
                sequence,running,pending,ts(now),ts(expires),id);
    }
    public List<WorkerNode> select(String keyword, String state, Instant now, int page, int size) {
        var filter=filter(keyword,state,now); var args=new ArrayList<>(filter.args()); args.add(size); args.add((long)(page-1)*size);
        return jdbc.query("SELECT * FROM worker_node"+filter.sql()+" ORDER BY worker_id LIMIT ? OFFSET ?",this::read,args.toArray());
    }
    public long count(String keyword, String state, Instant now) {
        var filter=filter(keyword,state,now);
        return jdbc.queryForObject("SELECT COUNT(*) FROM worker_node"+filter.sql(),Long.class,filter.args().toArray());
    }
    public Summary summary(Instant now) {
        return jdbc.queryForObject("SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN expires_at>? THEN 1 ELSE 0 END),0) online FROM worker_node",
                (rs,i)->new Summary(rs.getLong("total"),rs.getLong("online"),rs.getLong("total")-rs.getLong("online")),ts(now));
    }
    private record Filter(String sql,List<Object> args) { }
    private Filter filter(String keyword, String state, Instant now) {
        var sql=new StringBuilder(" WHERE 1=1"); var args=new ArrayList<Object>();
        if (!keyword.isEmpty()) {
            sql.append(" AND (LOWER(worker_id) LIKE ? ESCAPE '!' OR LOWER(display_name) LIKE ? ESCAPE '!')");
            var pattern="%"+keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            args.add(pattern); args.add(pattern);
        }
        if (!state.equals("ALL")) { sql.append(state.equals("ONLINE")?" AND expires_at>?":" AND expires_at<=?"); args.add(ts(now)); }
        return new Filter(sql.toString(),args);
    }
    private WorkerNode read(ResultSet rs,int i) throws SQLException {
        return new WorkerNode(rs.getString("worker_id"),rs.getString("display_name"),UUID.fromString(rs.getString("session_id")),rs.getLong("generation"),
                rs.getString("registration_fingerprint"),rs.getString("handler_keys_json"),rs.getInt("parallelism"),rs.getInt("max_pending"),rs.getInt("running_count"),rs.getInt("pending_count"),
                rs.getString("dispatch_transport"),rs.getString("dispatch_vhost"),rs.getString("dispatch_exchange"),rs.getString("dispatch_queue"),rs.getString("dispatch_routing_key"),rs.getLong("last_sequence"),
                rs.getTimestamp("registered_at").toInstant(),rs.getTimestamp("last_heartbeat_at").toInstant(),rs.getTimestamp("expires_at").toInstant());
    }
    private Timestamp ts(Instant value) { return Timestamp.from(value); }
}

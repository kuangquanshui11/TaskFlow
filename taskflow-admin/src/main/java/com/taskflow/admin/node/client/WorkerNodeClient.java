package com.taskflow.admin.node.client;

import com.taskflow.admin.remote.client.NodeSchedulerRemote;
import com.taskflow.admin.remote.model.RemoteErrors;
import java.time.Instant;
import java.util.Set;
import com.taskflow.admin.node.config.WorkerNodeClientProperties;
import com.taskflow.admin.node.dto.WorkerNodeResponse.*;
import com.taskflow.admin.node.exception.WorkerNodeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WorkerNodeClient {
    private final WorkerNodeClientProperties properties;
    private final JsonMapper json;
    private final NodeSchedulerRemote remote;
    public WorkerNodeClient(WorkerNodeClientProperties properties,JsonMapper json,NodeSchedulerRemote remote) {
        this.properties=properties;this.json=json;this.remote=remote;
    }
    public Page select(String query,int page,int size) {
        var data=json.readValue(get("select?"+query),Page.class);
        if (data==null || data.page()!=page || data.pageSize()!=size || data.total()<0 || data.records()==null
                || data.records().size()>size || data.records().size()>data.total() || data.observedAt()==null || data.summary()==null
                || data.summary().total()<data.total() || data.summary().online()<0 || data.summary().offline()<0
                || data.summary().online()+data.summary().offline()!=data.summary().total()) throw malformed();
        for (var node:data.records()) validate(node,data.observedAt());
        return data;
    }
    public View selectById(String query,String workerId) {
        var data=json.readValue(get("selectById?"+query),View.class);
        if (data==null || !workerId.equals(data.workerId())) throw malformed();
        validate(data,null); return data;
    }
    private String get(String path) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Node query inside SQL transaction");
        if (properties.controlToken().isBlank()) throw new WorkerNodeException(503,"SERVICE_NOT_CONFIGURED","节点查询服务尚未配置");
        try {
            return remote.get(RemoteErrors.action(path),RemoteErrors.query(path),RemoteErrors.headers(properties.controlToken(),java.util.UUID.randomUUID().toString()));
        } catch (RuntimeException unavailable) {
            if (RemoteErrors.status(unavailable)==404) throw new WorkerNodeException(404,"NODE_NOT_FOUND","节点不存在");
            throw new WorkerNodeException(503,"SCHEDULER_UNAVAILABLE","调度服务连接失败或超时，请稍后刷新");
        }
    }
    private void validate(View n,Instant observedAt) {
        if (n==null || n.workerId()==null || !n.workerId().matches("[a-z0-9][a-z0-9_.-]{0,99}")) throw malformed();
        if (n.displayName()==null || n.displayName().isBlank()
                || n.generation()<1 || n.handlerKeys()==null || n.handlerKeys().isEmpty() || n.handlerKeys().stream().anyMatch(s->s==null || s.isBlank())
                || n.parallelism()<1 || n.parallelism()>64 || n.maxPending()<n.parallelism() || n.maxPending()>10000
                || n.runningCount()<0 || n.pendingCount()<0 || n.runningCount()>n.parallelism() || (long)n.runningCount()+n.pendingCount()>n.maxPending()
                || !Set.of("http","rabbitmq").contains(n.dispatchTransport()) || !Set.of("ONLINE","OFFLINE").contains(n.status())
                || n.registeredAt()==null || n.lastHeartbeatAt()==null || n.expiresAt()==null
                || !n.expiresAt().isAfter(n.lastHeartbeatAt()) || n.dispatchEligible() && (!n.target() || !n.status().equals("ONLINE"))
                || observedAt!=null && n.status().equals("ONLINE")!=n.expiresAt().isAfter(observedAt)) throw malformed();
        if (n.dispatchTransport().equals("rabbitmq") && (n.dispatchVhost()==null || n.dispatchVhost().isBlank()
                || n.dispatchExchange()==null || n.dispatchExchange().isBlank() || n.dispatchQueue()==null || n.dispatchQueue().isBlank()
                || n.dispatchRoutingKey()==null || n.dispatchRoutingKey().isBlank())) throw malformed();
    }
    private WorkerNodeException malformed() { return new WorkerNodeException(503,"SCHEDULER_PROTOCOL_ERROR","调度服务返回的节点格式不合法"); }
}

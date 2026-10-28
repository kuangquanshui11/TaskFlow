package com.taskflow.worker.node.client;

import com.taskflow.worker.remote.client.SchedulerNodeRemote;
import com.taskflow.worker.remote.model.RemoteErrors;
import com.taskflow.worker.node.config.WorkerRegistrationProperties;
import com.taskflow.worker.node.dto.WorkerNodeProtocol.*;
import com.taskflow.worker.node.exception.NodeClientException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WorkerNodeClient {
    private final WorkerRegistrationProperties properties;
    private final JsonMapper json;
    private final SchedulerNodeRemote remote;
    public WorkerNodeClient(WorkerRegistrationProperties properties,JsonMapper json,SchedulerNodeRemote remote) {
        this.properties=properties;this.json=json;this.remote=remote;
    }
    public Acknowledgement register(Registration request) { return send("insert",request,request.workerId(),request.sessionId(),0,0); }
    public Acknowledgement heartbeat(Heartbeat request) { return send("updateHeartbeat",request,request.workerId(),request.sessionId(),request.generation(),request.sequence()); }
    private Acknowledgement send(String action,Object body,String workerId,java.util.UUID session,long generation,long sequence) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Heartbeat inside SQL transaction");
        if (properties.nodeToken().isBlank()) throw new NodeClientException(503,"NODE_TOKEN_NOT_CONFIGURED");
        String response;
        try {
            response=remote.update(action,body,RemoteErrors.headers(properties.nodeToken(),session.toString()));
        } catch (RuntimeException failed) {
            int status=RemoteErrors.status(failed);throw new NodeClientException(status,"NODE_HTTP_"+status);
        }
        try {
            var ack=json.readValue(response,Acknowledgement.class);
            if (ack==null || !workerId.equals(ack.workerId()) || !session.equals(ack.sessionId()) || ack.generation()<1
                    || generation>0 && generation!=ack.generation() || ack.lastSequence()<sequence || ack.expiresAt()==null)
                throw new NodeClientException(503,"NODE_PROTOCOL_ERROR");
            return ack;
        } catch (tools.jackson.core.JacksonException malformed) {
            throw new NodeClientException(503,"NODE_PROTOCOL_ERROR");
        }
    }
}

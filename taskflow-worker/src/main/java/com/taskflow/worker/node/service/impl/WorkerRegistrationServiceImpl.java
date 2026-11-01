package com.taskflow.worker.node.service.impl;

import java.util.UUID;
import com.taskflow.worker.dispatch.config.DispatchProperties;
import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.service.*;
import com.taskflow.worker.node.client.WorkerNodeClient;
import com.taskflow.worker.node.config.WorkerRegistrationProperties;
import com.taskflow.worker.node.dto.WorkerNodeProtocol.*;
import com.taskflow.worker.node.exception.NodeClientException;
import com.taskflow.worker.node.service.IWorkerRegistrationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class WorkerRegistrationServiceImpl implements IWorkerRegistrationService {
    private final WorkerNodeClient client;
    private final WorkerRegistrationProperties properties;
    private final ExecutionProperties execution;
    private final DispatchProperties dispatch;
    private final IExecutionService executions;
    private final IHandlerRegistryService handlers;
    private final String vhost;
    private final UUID sessionId=UUID.randomUUID();
    private long generation;
    private long sequence;
    public WorkerRegistrationServiceImpl(WorkerNodeClient client,WorkerRegistrationProperties properties,ExecutionProperties execution,
            DispatchProperties dispatch,IExecutionService executions,IHandlerRegistryService handlers,@Value("${spring.rabbitmq.virtual-host}") String vhost) {
        this.client=client;this.properties=properties;this.execution=execution;this.dispatch=dispatch;this.executions=executions;this.handlers=handlers;this.vhost=vhost;
    }
    @Override
    @Transactional(propagation=Propagation.NEVER)
    public synchronized void pulse() {
        try {
            if (generation==0) {
                boolean mq=dispatch.transport().equals("rabbitmq");
                var request=new Registration(properties.workerId(),properties.displayName(),sessionId,handlers.keys().stream().sorted().toList(),
                        execution.parallelism(),execution.maxPending(),dispatch.transport(),mq?vhost:null,mq?dispatch.exchange():null,mq?dispatch.queue():null,mq?dispatch.routingKey():null);
                generation=client.register(request).generation();
            }
            var load=executions.selectLoad();
            client.heartbeat(new Heartbeat(properties.workerId(),sessionId,generation,++sequence,load.runningCount(),load.pendingCount()));
        } catch (NodeClientException failure) {
            // 记录缺失或会话已变化时用原 session 重注册；绝不换一个新 session 绕过旧会话隔离。
            if (failure.status()==404 || failure.status()==409) generation=0;
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Worker heartbeat not confirmed. workerId={}, code={}",properties.workerId(),failure.getMessage());
        }
    }
}

package com.taskflow.worker.execution.service.impl;

import java.util.UUID;
import java.util.concurrent.*;
import com.taskflow.worker.execution.client.SchedulerLeaseClient;
import com.taskflow.worker.execution.config.ExecutionLeaseProperties;
import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.execution.service.IExecutionLeaseService;
import com.taskflow.worker.node.config.WorkerRegistrationProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

@Service
public class ExecutionLeaseServiceImpl implements IExecutionLeaseService {
    private final SchedulerLeaseClient client;
    private final ExecutionLeaseProperties properties;
    private final WorkerRegistrationProperties node;
    // 执行会话独立于节点注册 generation；进程重启不继承旧执行资格。
    private final UUID sessionId = UUID.randomUUID();
    private final ScheduledThreadPoolExecutor renewals;
    private final ConcurrentMap<ExecutionContext, ScheduledFuture<?>> active = new ConcurrentHashMap<>();

    public ExecutionLeaseServiceImpl(SchedulerLeaseClient client, ExecutionLeaseProperties properties,
                                     ExecutionProperties execution, WorkerRegistrationProperties node) {
        this.client = client; this.properties = properties; this.node = node;
        renewals = new ScheduledThreadPoolExecutor(execution.parallelism(),
                Thread.ofPlatform().name("taskflow-lease-", 0).factory());
        renewals.setRemoveOnCancelPolicy(true);
    }
    @Override public ExecutionContext open(Dispatch dispatch) {
        var request = new LeaseRequest(dispatch.attemptId(), dispatch.runId(), dispatch.leaseVersion(), node.workerId(), sessionId);
        long started = System.nanoTime();
        var ack = client.updateLease(request);
        var context = new ExecutionContext(dispatch, request, ack, properties.safetyMargin(), started);
        long interval = properties.renewalInterval().toMillis();
        try {
            active.put(context, renewals.scheduleWithFixedDelay(() -> renew(context, request), interval, interval, TimeUnit.MILLISECONDS));
            return context;
        } catch (RuntimeException failure) { context.close(); throw failure; }
    }
    private void renew(ExecutionContext context, LeaseRequest request) {
        if (!context.renewable()) return;
        long started = System.nanoTime();
        try { context.renewed(client.updateLease(request), started); }
        catch (ExecutionException rejected) { context.cancel("LEASE_LOST"); }
        catch (IllegalArgumentException invalid) { context.cancel("LEASE_LOST"); }
        catch (RuntimeException unavailable) {
            // 网络不确定不等于立即失租；只在最后确认的安全期限内安排下一次显式请求。
            context.cancelled();
        }
    }
    @Override public void close(ExecutionContext context) {
        context.close();
        var task = active.remove(context);
        if (task != null) task.cancel(true);
    }
    @Override public long watchdogIntervalMillis() { return properties.watchdogInterval().toMillis(); }
    @PreDestroy public void shutdown() {
        active.forEach((context, task) -> { context.cancel("LEASE_LOST"); task.cancel(true); });
        active.clear(); renewals.shutdownNow();
    }
}

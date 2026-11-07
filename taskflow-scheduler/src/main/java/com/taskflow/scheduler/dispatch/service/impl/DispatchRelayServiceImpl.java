package com.taskflow.scheduler.dispatch.service.impl;

import java.time.Instant;
import java.util.UUID;
import com.taskflow.scheduler.dispatch.config.DispatchProperties;
import com.taskflow.scheduler.dispatch.dto.DispatchEvent;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.dispatch.model.OutboxEvent;
import com.taskflow.scheduler.dispatch.service.*;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.Dispatch;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "taskflow.dispatch.transport", havingValue = "rabbitmq", matchIfMissing = true)
public class DispatchRelayServiceImpl implements IDispatchRelayService {
    private final DispatchOutboxMapper mapper;
    private final RunDataMapper runs;
    private final IDispatchPublisher publisher;
    private final DispatchProperties properties;
    private final TransactionTemplate transactions;
    private final com.taskflow.scheduler.node.service.IWorkerNodeService nodes;
    private final tools.jackson.databind.json.JsonMapper json;
    public DispatchRelayServiceImpl(DispatchOutboxMapper mapper, IDispatchPublisher publisher,
            DispatchProperties properties, PlatformTransactionManager manager, RunDataMapper runs,
            com.taskflow.scheduler.node.service.IWorkerNodeService nodes,tools.jackson.databind.json.JsonMapper json) {
        this.mapper = mapper; this.publisher = publisher; this.properties = properties;
        this.runs = runs;
        transactions = new TransactionTemplate(manager);
        this.nodes=nodes; this.json=json;
    }
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void relay() {
        for (UUID id : mapper.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = transactions.execute(status -> claim(id));
                if (claimed == null) continue;
                var event = claimed.event();
                try {
                    // confirm 等待发生在 SQL 事务外；确认后崩溃会重发同一不可变事件。
                    publisher.publish(event);
                } catch (RuntimeException failed) {
                    var now = Instant.now();
                    long factor = 1L << Math.min(event.publishAttempts() - 1, 16);
                    var delay = properties.retryDelay().multipliedBy(factor);
                    if (delay.compareTo(properties.maxRetryDelay()) > 0) delay = properties.maxRetryDelay();
                    var next = now.plus(delay);
                    boolean exhausted = event.publishAttempts() >= properties.maxPublishAttempts();
                    transactions.executeWithoutResult(status -> {
                        if (mapper.failed(event, exhausted, "MQ_PUBLISH_UNCONFIRMED", next, now))
                            appendEvent(claimed, "MQ_PUBLISH_UNCONFIRMED", now,
                                    exhausted ? "FAILED" : "READY", "MQ_PUBLISH_UNCONFIRMED");
                    });
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("MQ publish not confirmed. eventId={}, attempt={}, type={}",
                            id, event.publishAttempts(), failed.getClass().getSimpleName());
                    continue;
                }
                transactions.executeWithoutResult(status -> {
                    var now = Instant.now();
                    if (mapper.published(event, now))
                        appendEvent(claimed, "MQ_PUBLISHED", now, "PUBLISHED", null);
                });
            } catch (RuntimeException failed) {
                // 留下的 SENDING 租约到期后可由其他 Relay 接管；一条失败不阻止其他事件。
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Outbox relay failed. eventId={}, type={}", id, failed.getClass().getSimpleName());
            }
        }
    }
    private record ClaimedPublish(OutboxEvent event, Dispatch command) { }

    private ClaimedPublish claim(UUID id) {
        var event = mapper.find(id, true).orElseThrow();
        var now = Instant.now();
        if (event.nextPublishAt().isAfter(now) || !(event.state().equals("READY")
                || event.state().equals("SENDING") && !event.claimUntil().isAfter(now))) return null;
        if (!event.expiresAt().isAfter(now)) { mapper.cancel(event.attemptId()); return null; }
        var command = json.readValue(event.messageJson(), DispatchEvent.class).command();
        if (!nodes.canDispatch(event.target(), command.snapshot().handlerKey())) {
            if (!mapper.deferPublication(event, now.plus(properties.nodeRecheckDelay()), now))
                throw new IllegalStateException("Outbox 状态或领取版本已变化");
            return null;
        }
        if (event.publishAttempts() >= properties.maxPublishAttempts()) {
            // 崩溃也计入次数，最后一次发送结果未知，不自动产生新的业务 Attempt。
            mapper.exhaust(id);
            appendEvent(new ClaimedPublish(event, command), "MQ_PUBLISH_EXHAUSTED", now, "FAILED", "MQ_PUBLISH_UNCONFIRMED");
            return null;
        }
        mapper.claim(id, now.plus(properties.claimLease()));
        var claimed = new ClaimedPublish(mapper.find(id, false).orElseThrow(), command);
        appendEvent(claimed, "MQ_PUBLISH_CLAIMED", now, "SENDING", null);
        return claimed;
    }

    private void appendEvent(ClaimedPublish claimed, String type, Instant now, String state, String code) {
        var event = claimed.event();
        var command = claimed.command();
        var workerId = event.target() == null ? null : event.target().workerId();
        runs.appendAttemptEvent(command.runId(), event.attemptId(), workerId, command.leaseVersion(),
                type, now, event.publishAttempts(), state, code, null);
    }
}

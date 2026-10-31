package com.taskflow.scheduler.observability.service.impl;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.node.mapper.WorkerNodeMapper;
import com.taskflow.scheduler.observability.service.IMetricsSampler;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name = "taskflow.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class MetricsSamplerImpl implements IMetricsSampler {
    private final DispatchOutboxMapper outbox;
    private final RunDataMapper runs;
    private final WorkerNodeMapper nodes;
    private final Clock clock;
    private final List<AtomicLong> values = new ArrayList<>();
    private final AtomicLong lastSuccess = new AtomicLong(-1);
    private final Counter ok;
    private final Counter failed;
    private static final List<String> STATES = List.of("WAITING", "READY", "SENDING", "PUBLISHED", "FAILED", "CANCELLED");
    private final Map<String, AtomicLong> backlog = new LinkedHashMap<>();

    public MetricsSamplerImpl(DispatchOutboxMapper outbox, RunDataMapper runs, WorkerNodeMapper nodes,
            MeterRegistry registry, @Qualifier("metricsClock") Clock clock) {
        this.outbox = outbox; this.runs = runs; this.nodes = nodes;
        this.clock = clock;
        for (var state : STATES) {
            var value = new AtomicLong(-1); backlog.put(state, value);
            Gauge.builder("taskflow.dispatch.outbox.backlog", value, AtomicLong::get)
                    .tag("state", state).register(registry);
        }
        register(registry, "taskflow.dispatch.outbox.publish.overdue", null);
        register(registry, "taskflow.dispatch.outbox.claim.expired", null);
        register(registry, "taskflow.dispatch.attempt.dispatch.overdue", null);
        register(registry, "taskflow.dispatch.attempt.lease.expired", null);
        register(registry, "taskflow.dispatch.attempt.lease.expiring", null);
        register(registry, "taskflow.worker.node.heartbeat.age", "seconds");
        // 回调只读取内存时间戳；失败期间 age 继续增加，不查询数据库。
        Gauge.builder("taskflow.metrics.sample.age", lastSuccess,
                value -> value.get() < 0 ? -1 : Math.max(0, (clock.millis() - value.get()) / 1000.0))
                .baseUnit("seconds").register(registry);
        ok = registry.counter("taskflow.metrics.sample", "outcome", "ok");
        failed = registry.counter("taskflow.metrics.sample", "outcome", "failed");
    }

    private void register(MeterRegistry registry, String name, String unit) {
        var value = new AtomicLong(-1); values.add(value);
        Gauge.builder(name, value, AtomicLong::get).baseUnit(unit).register(registry);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public synchronized void sample() {
        var now = clock.instant();
        try {
            var counts = outbox.countByState();
            long publish = outbox.countPublishOverdue(now);
            long claim = outbox.countClaimExpired(now);
            long dispatch = runs.countDispatchOverdue(now);
            long expired = runs.countLeaseExpired(now);
            long expiring = runs.countLeaseExpiring(now);
            long heartbeat = nodes.oldestOnlineHeartbeat(now)
                    .map(at -> Math.max(0, Duration.between(at, now).getSeconds())).orElse(-1L);
            // 全部查询成功才发布本轮值；各 SELECT 独立释放连接，不构成数据库快照。
            for (var state : STATES) backlog.get(state).set(counts.getOrDefault(state, 0L));
            long[] sampled = {publish, claim, dispatch, expired, expiring, heartbeat};
            for (int i = 0; i < sampled.length; i++) values.get(i).set(sampled[i]);
            lastSuccess.set(now.toEpochMilli());
            ok.increment();
        } catch (RuntimeException failure) {
            failed.increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Metrics sample failed. type={}", failure.getClass().getSimpleName());
        }
    }
}

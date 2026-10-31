package com.taskflow.worker.observability.service.impl;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.observability.service.IMetricsSampler;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name = "taskflow.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class MetricsSamplerImpl implements IMetricsSampler {
    private final ExecutionMapper executions;
    private final Clock clock;
    private final List<AtomicLong> values = new ArrayList<>();
    private final AtomicLong lastSuccess = new AtomicLong(-1);
    private final Counter ok;
    private final Counter failed;


    public MetricsSamplerImpl(ExecutionMapper executions,
            MeterRegistry registry, @Qualifier("metricsClock") Clock clock) {
        this.executions = executions;
        this.clock = clock;

        register(registry, "taskflow.worker.result.unreported", null);
        register(registry, "taskflow.worker.execution.backlog", null);
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
            long unreported = executions.countUnreported();
            long accepted = executions.countAccepted();
            long[] sampled = {unreported, accepted};
            for (int i = 0; i < sampled.length; i++) values.get(i).set(sampled[i]);
            lastSuccess.set(now.toEpochMilli());
            ok.increment();
        } catch (RuntimeException failure) {
            failed.increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Metrics sample failed. type={}", failure.getClass().getSimpleName());
        }
    }
}

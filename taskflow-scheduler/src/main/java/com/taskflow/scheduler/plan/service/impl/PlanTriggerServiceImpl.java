package com.taskflow.scheduler.plan.service.impl;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.scheduler.execution.service.IRunService;
import com.taskflow.scheduler.plan.config.PlanTriggerProperties;
import com.taskflow.scheduler.plan.dto.PlanSyncRequest.Configuration;
import com.taskflow.scheduler.plan.mapper.PlanMapper;
import com.taskflow.scheduler.plan.model.PlanRecords.*;
import com.taskflow.scheduler.plan.service.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PlanTriggerServiceImpl implements IPlanTriggerService {
    private final PlanMapper plans;
    private final IPlanCalculationService calculations;
    private final IRunService runs;
    private final PlanTriggerProperties properties;
    private final Clock clock;
    private final JsonMapper json;
    private final TransactionTemplate transactions;

    public PlanTriggerServiceImpl(PlanMapper plans, IPlanCalculationService calculations, IRunService runs,
            PlanTriggerProperties properties, @Qualifier("planClock") Clock clock, JsonMapper json, PlatformTransactionManager manager) {
        this.plans = plans; this.calculations = calculations; this.runs = runs; this.properties = properties;
        this.clock = clock; this.json = json; this.transactions = new TransactionTemplate(manager);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void scanDue() {
        for (long jobId : plans.due(clock.instant(), properties.batchSize())) {
            if (Thread.currentThread().isInterrupted()) return;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    transactions.executeWithoutResult(status -> trigger(jobId));
                    break;
                } catch (DuplicateKeyException | TransientDataAccessException race) {
                    if (attempt == 2) logFailure(jobId, race);
                } catch (RuntimeException failure) {
                    // 一条计划失败后整体回滚，保留原到期时间，下一轮可恢复；其他 Job 继续扫描。
                    logFailure(jobId, failure);
                    break;
                }
            }
        }
    }

    private void trigger(long jobId) {
        var current = plans.find(jobId, true);
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (current.isEmpty()) return;
        var plan = current.get();
        if (plan.deleted() || !plan.enabled() || plan.calculationState() != CalculationState.READY
                || plan.nextFireTime() == null || plan.nextFireTime().isAfter(now)) return;
        Configuration config;
        Calculation next;
        Snapshot snapshot;
        try {
            config = json.readValue(plan.snapshotJson(), Configuration.class);
            if (config == null || !"CRON".equals(config.scheduleType()) || !config.enabled()
                    || !("SKIP".equals(config.misfirePolicy()) || "FIRE_ONCE".equals(config.misfirePolicy())))
                throw new IllegalArgumentException("Invalid stored schedule");
            next = calculations.calculate(config, false, now);
            snapshot = new Snapshot(plan.jobId(), plan.configVersion(), config.name(), config.handlerKey(),
                    config.payloadJson() == null ? null : json.readTree(config.payloadJson()), config.timeoutSeconds(),
                    config.maxAttempts(), config.retryIntervalSeconds(), config.scheduleType(), config.cronExpression(),
                    config.timeZone(), config.misfirePolicy(), config.enabled(), config.description());
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
            plans.advance(plan, new Calculation(null, now, CalculationState.INVALID));
            logFailure(jobId, invalid);
            return;
        }
        boolean missed = plan.nextFireTime().isBefore(now.minus(properties.misfireGrace()));
        if (!missed || "FIRE_ONCE".equals(config.misfirePolicy()))
            runs.insertScheduled(snapshot, plan.nextFireTime(), now);
        // 与 Run/首次 Attempt 同一事务提交；下一时间严格晚于本次决策，错过多次也不批量补跑。
        plans.advance(plan, next);
    }

    private void logFailure(long jobId, RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Plan trigger failed. jobId={}, type={}", jobId, failure.getClass().getSimpleName());
    }
}

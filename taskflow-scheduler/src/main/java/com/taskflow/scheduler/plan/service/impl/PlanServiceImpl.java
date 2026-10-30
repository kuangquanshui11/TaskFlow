package com.taskflow.scheduler.plan.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HexFormat;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.plan.dto.PlanSyncRequest;
import com.taskflow.scheduler.plan.mapper.PlanMapper;
import com.taskflow.scheduler.plan.model.PlanRecords.*;
import com.taskflow.scheduler.plan.service.IPlanService;
import com.taskflow.scheduler.plan.service.IPlanCalculationService;
import com.taskflow.scheduler.plan.config.PlanProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PlanServiceImpl implements IPlanService {
    private final PlanMapper mapper;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final IPlanCalculationService calculations;
    private final PlanProperties properties;
    private final Clock clock;

    public PlanServiceImpl(PlanMapper mapper, JsonMapper json, PlatformTransactionManager manager,
                           IPlanCalculationService calculations, PlanProperties properties, @Qualifier("planClock") Clock clock) {
        this.mapper = mapper; this.json = json; this.transaction = new TransactionTemplate(manager);
        this.calculations = calculations; this.properties = properties; this.clock = clock;
    }

    @Override
    public Acknowledgement update(PlanSyncRequest request) {
        validate(request);
        String snapshot = json.writeValueAsString(request.configuration());
        String fingerprint = fingerprint(request, snapshot);
        // 首次插入和同版本并发可能争用唯一键或发生死锁。每次重新开启事务后再检查幂等记录。
        for (int attempt = 0; ; attempt++) {
            try { return transaction.execute(status -> apply(request, snapshot, fingerprint)); }
            catch (DuplicateKeyException | TransientDataAccessException race) { if (attempt >= 2) throw race; }
        }
    }

    private Acknowledgement apply(PlanSyncRequest input, String snapshot, String fingerprint) {
        var receipts = mapper.receipts(input.eventId(), input.jobId(), input.configVersion());
        if (!receipts.isEmpty()) {
            if (receipts.size() != 1 || !receipts.getFirst().equals(
                    new Receipt(input.eventId(), input.jobId(), input.configVersion(), fingerprint)))
                throw new RunException(409, "PLAN_SYNC_CONFLICT", "事件标识或配置版本对应不同配置");
            var current = mapper.find(input.jobId(), true);
            // Inbox 证明历史接收，不能证明副本仍在。匹配原事件才允许恢复缺失或落后的副本。
            if (current.isPresent() && current.get().configVersion() >= input.configVersion())
                return ack(input, current.get().configVersion(), "DUPLICATE");
        } else {
            mapper.insertReceipt(new Receipt(input.eventId(), input.jobId(), input.configVersion(), fingerprint), Instant.now());
        }
        var current = mapper.find(input.jobId(), true);
        if (current.isPresent() && current.get().configVersion() > input.configVersion())
            return ack(input, current.get().configVersion(), "SUPERSEDED");
        // 副本丢失后仍以 Inbox 保留的最高版本为恢复下界，防止迟到旧事件抢先重建并重新启用已删除任务。
        if (mapper.highestReceivedVersion(input.jobId()) > input.configVersion())
            throw new RunException(409, "PLAN_RESTORE_REQUIRES_LATEST", "副本恢复需要最高已接收版本的原事件");
        var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var calculation = current.filter(plan -> preserveCalculation(plan, input))
                .map(plan -> new Calculation(plan.nextFireTime(), plan.calculatedAt(), plan.calculationState()))
                .orElseGet(() -> calculations.calculate(input.configuration(), input.deleted(), now));
        var plan = new Plan(input.jobId(), input.configVersion(), input.deleted(),
                !input.deleted() && input.configuration().enabled(), snapshot, now,
                calculation.nextFireTime(), calculation.calculatedAt(), calculation.state());
        if (current.isEmpty()) mapper.insert(plan);
        else mapper.update(plan);
        return ack(input, input.configVersion(), "APPLIED");
    }

    @Override
    public Plan selectByJobId(long jobId) {
        if (jobId < 1) throw new IllegalArgumentException("jobId must be positive");
        return mapper.find(jobId, false).orElseThrow(() -> new RunException(404, "PLAN_NOT_FOUND", "计划副本不存在"));
    }

    @Override
    public java.util.List<com.taskflow.scheduler.plan.dto.PlanVersion> selectVersions(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id < 1))
            throw new IllegalArgumentException("1..100 positive job ids required");
        return mapper.versions(ids);
    }

    private boolean preserveCalculation(Plan plan, PlanSyncRequest input) {
        if (plan.calculationState() == CalculationState.PENDING || plan.calculationState() == CalculationState.INVALID
                || plan.deleted() != input.deleted()) return false;
        var before = json.readValue(plan.snapshotJson(), PlanSyncRequest.Configuration.class);
        var after = input.configuration();
        // 参数或说明变更不能不停推迟即将到期的计划。改变实际调度规则时才以接收时间重新计算。
        return before.enabled() == after.enabled() && before.scheduleType().equals(after.scheduleType())
                && java.util.Objects.equals(before.cronExpression(), after.cronExpression()) && before.timeZone().equals(after.timeZone());
    }

    @Override
    public void initializePending() {
        for (long jobId : mapper.pending(properties.batchSize())) {
            transaction.executeWithoutResult(status -> {
                var current = mapper.find(jobId, true);
                if (current.isEmpty() || current.get().calculationState() != CalculationState.PENDING) return;
                var plan = current.get();
                var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                Calculation calculation;
                try {
                    var config = json.readValue(plan.snapshotJson(), PlanSyncRequest.Configuration.class);
                    calculation = calculations.calculate(config, plan.deleted(), now);
                } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
                    calculation = new Calculation(null, now, CalculationState.INVALID);
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Invalid stored plan. jobId={}, configVersion={}", jobId, plan.configVersion());
                }
                mapper.initialize(plan, calculation);
            });
        }
    }

    private Acknowledgement ack(PlanSyncRequest input, long version, String result) {
        return new Acknowledgement(input.eventId(), input.jobId(), input.configVersion(), version, result);
    }

    private void validate(PlanSyncRequest input) {
        var config = input.configuration();
        try { ZoneId.of(config.timeZone()); }
        catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid time zone", invalid); }
        if ("CRON".equals(config.scheduleType())) CronExpression.parse(config.cronExpression());
        else if (config.cronExpression() != null) throw new IllegalArgumentException("Manual plan cannot contain Cron");
        if (config.payloadJson() != null) {
            try {
                if (config.payloadJson().getBytes(StandardCharsets.UTF_8).length > 65536
                        || !json.readTree(config.payloadJson()).isObject()) throw new IllegalArgumentException("Invalid payload");
            } catch (tools.jackson.core.JacksonException malformed) { throw new IllegalArgumentException("Invalid payload", malformed); }
        }
    }

    private String fingerprint(PlanSyncRequest input, String snapshot) {
        try {
            String canonical = input.jobId() + ":" + input.configVersion() + ":" + input.deleted() + ":" + snapshot;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

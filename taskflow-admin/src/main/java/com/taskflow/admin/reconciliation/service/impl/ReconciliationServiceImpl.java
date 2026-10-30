package com.taskflow.admin.reconciliation.service.impl;

import java.time.Instant;
import java.util.*;
import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.jobsync.config.JobSyncProperties;
import com.taskflow.admin.jobsync.exception.PlanQueryException;
import com.taskflow.admin.reconciliation.client.PlanReplicaClient;
import com.taskflow.admin.reconciliation.config.ReconciliationProperties;
import com.taskflow.admin.reconciliation.mapper.ReconciliationMapper;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.*;
import com.taskflow.admin.reconciliation.service.IReconciliationService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@EnableConfigurationProperties(ReconciliationProperties.class)
@Transactional(propagation = Propagation.NEVER)
public class ReconciliationServiceImpl implements IReconciliationService {
    private final ReconciliationMapper mapper;
    private final PlanReplicaClient client;
    private final ReconciliationProperties properties;
    private final JobSyncProperties sync;
    private final TransactionTemplate transaction;

    public ReconciliationServiceImpl(ReconciliationMapper mapper, PlanReplicaClient client, ReconciliationProperties properties,
                                     JobSyncProperties sync, PlatformTransactionManager manager) {
        this.mapper = mapper; this.client = client; this.properties = properties; this.sync = sync;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public Scan insert(UUID requestId, long afterJobId, int batchSize) {
        if (requestId == null || afterJobId < 0 || batchSize < 1 || batchSize > properties.maxBatchSize())
            throw JobException.invalid("requestId 必填，afterJobId 非负，batchSize 不得超过配置上限");
        var sources = new ArrayList<Source>();
        boolean created = Boolean.TRUE.equals(transaction.execute(status -> {
            Instant allowed = mapper.lockGate("scan");
            var existing = mapper.scan(requestId);
            if (existing.isPresent()) {
                if (existing.get().afterJobId() != afterJobId || existing.get().batchSize() != batchSize) throw conflict();
                return false;
            }
            Instant now = mapper.now();
            requireAllowed(now, allowed);
            var page = mapper.sources(afterJobId, batchSize + 1);
            sources.addAll(page.stream().limit(batchSize).toList());
            Instant lease = now.plus(sync.connectTimeout()).plus(sync.requestTimeout()).plusSeconds(30);
            mapper.insertScan(new Scan(requestId, afterJobId, batchSize,
                    sources.isEmpty() ? afterJobId : sources.getLast().jobId(), page.size() > batchSize,
                    "RUNNING", actor(), now, lease, null, List.of()));
            mapper.gate("scan", lease);
            return true;
        }));
        if (!created) return selectById(requestId);

        Map<Long, Replica> replicas = Map.of();
        String error = null;
        try {
            if (!sources.isEmpty()) replicas = client.select(sources.stream().map(Source::jobId).toList(), requestId);
        } catch (PlanQueryException unavailable) { error = unavailable.code(); }
        var checks = new ArrayList<Check>();
        Instant checkedAt = mapper.now();
        for (var source : sources) {
            var replica = replicas.get(source.jobId());
            String difference = source.eventId() == null ? "SOURCE_MISSING" : error != null ? "UNAVAILABLE"
                    : replica == null ? "MISSING" : replica.configVersion() > source.configVersion() ? "AHEAD"
                    : replica.configVersion() < source.configVersion() ? "BEHIND"
                    : replica.deleted() != source.deleted() || replica.enabled() != source.enabled() ? "CONFLICT" : "MATCH";
            checks.add(new Check(UUID.randomUUID(), requestId, source, replica, difference, error, checkedAt, null));
        }
        transaction.executeWithoutResult(status -> {
            mapper.lockGate("scan");
            Instant now = mapper.now();
            var scan = mapper.scan(requestId).orElseThrow();
            if (!scan.leaseUntil().isAfter(now)) return;
            mapper.complete(requestId, checks, now);
            mapper.gate("scan", now.plus(properties.scanInterval()));
        });
        return selectById(requestId);
    }

    @Override
    public Scan selectById(UUID requestId) {
        var scan = mapper.scan(requestId).orElseThrow(() -> missing("对账批次不存在"));
        if ("RUNNING".equals(scan.state()) && !scan.leaseUntil().isAfter(mapper.now()))
            return new Scan(scan.requestId(), scan.afterJobId(), scan.batchSize(), scan.nextAfterJobId(), scan.hasMore(),
                    "EXPIRED", scan.actor(), scan.startedAt(), scan.leaseUntil(), scan.finishedAt(), scan.checks());
        return scan;
    }

    @Override
    public Repair repair(UUID requestId, UUID checkId) {
        if (requestId == null || checkId == null) throw JobException.invalid("requestId/checkId 必填");
        return transaction.execute(status -> {
            Instant allowed = mapper.lockGate("repair");
            var existing = mapper.repair(requestId);
            if (existing.isPresent()) {
                if (!existing.get().checkId().equals(checkId)) throw conflict();
                return existing.get();
            }
            Instant now = mapper.now();
            requireAllowed(now, allowed);
            var check = mapper.check(checkId).orElseThrow(() -> missing("对账明细不存在"));
            String outcome;
            boolean consumed = false;
            if (check.repairRequestId() != null) outcome = "ALREADY_REQUESTED";
            else if (!Set.of("MISSING", "BEHIND").contains(check.difference())) outcome = "NOT_REPAIRABLE";
            else if (!check.checkedAt().plus(properties.maxCheckAge()).isAfter(now)) outcome = "EXPIRED";
            else {
                var current = mapper.lockSource(check.source().jobId());
                if (current.isEmpty() || !current.get().equals(check.source())) outcome = "STALE";
                else {
                    // 状态条件与 Relay 租约竞争原子化：不打断 PENDING/SENDING，不生成配置版本或新事件。
                    outcome = mapper.requeue(check.source().eventId(), now) ? "ENQUEUED" : "ALREADY_PENDING";
                    consumed = true;
                }
            }
            var repair = new Repair(requestId, checkId, check.source().eventId(), outcome, actor(), now);
            mapper.insertRepair(repair, consumed);
            mapper.gate("repair", now.plus(properties.repairInterval()));
            return repair;
        });
    }

    @Override
    public Repair selectRepair(UUID requestId) {
        return mapper.repair(requestId).orElseThrow(() -> missing("修复记录不存在"));
    }
    private void requireAllowed(Instant now, Instant allowed) {
        if (now.isBefore(allowed)) throw new PlanQueryException(429, "RECONCILIATION_RATE_LIMITED", "对账或修复正在限速，请稍后使用同一请求标识重试");
    }
    private String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) throw new IllegalStateException("Authenticated actor required");
        return authentication.getName();
    }
    private JobException conflict() { return new JobException(JobException.Kind.CONFLICT, "请求标识已被其他参数使用"); }
    private JobException missing(String message) { return new JobException(JobException.Kind.NOT_FOUND, message); }
}

package com.taskflow.admin.reconciliation.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.taskflow.admin.jobsync.config.JobSyncProperties;
import com.taskflow.admin.jobsync.exception.PlanQueryException;
import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.reconciliation.client.PlanReplicaClient;
import com.taskflow.admin.reconciliation.config.AutomaticReconciliationProperties;
import com.taskflow.admin.reconciliation.mapper.ReconciliationMapper;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.*;
import com.taskflow.admin.reconciliation.service.IAutomaticReconciliationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Transactional(propagation = Propagation.NEVER)
public class AutomaticReconciliationServiceImpl implements IAutomaticReconciliationService {
    private static final String GATE = "automatic";
    private final ReconciliationMapper mapper;
    private final PlanReplicaClient client;
    private final AutomaticReconciliationProperties automatic;
    private final JobSyncProperties sync;
    private final TransactionTemplate transaction;

    public AutomaticReconciliationServiceImpl(ReconciliationMapper mapper, PlanReplicaClient client,
                                               AutomaticReconciliationProperties automatic,
                                               JobSyncProperties sync, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.client = client;
        this.automatic = automatic;
        this.sync = sync;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public void scanNext() {
        if (!automatic.enabled()) return;
        var claim = transaction.execute(status -> claim());
        if (claim == null) return;

        Batch batch;
        try {
            batch = claim.round.phase() == Phase.ADMIN ? scanAdmin(claim) : scanScheduler(claim);
        } catch (PlanQueryException unavailable) {
            batch = new Batch(claim.round.phase(), claim.round.afterJobId(), claim.round.schedulerUpperJobId(),
                    0, List.of(), unavailable.code());
        }

        Batch result = batch;
        transaction.executeWithoutResult(status -> save(claim, result));
    }

    private Claim claim() {
        var available = mapper.lockGate(GATE);
        var now = mapper.now();
        if (available.isAfter(now)) return null;
        var round = mapper.latestRound().orElse(null);
        if (round == null || round.phase() == Phase.COMPLETED) {
            mapper.startRound(UUID.randomUUID(), now);
            round = mapper.latestRound().orElseThrow();
        }
        var leaseId = UUID.randomUUID();
        mapper.claimRound(round.id(), leaseId);
        mapper.gate(GATE, now.plus(sync.connectTimeout()).plus(sync.requestTimeout()).plusSeconds(30));
        return new Claim(round, leaseId, now);
    }

    private Batch scanAdmin(Claim claim) {
        var round = claim.round;
        int limit = automatic.batchSize();
        var page = mapper.sources(round.afterJobId(), round.adminUpperJobId(), limit + 1);
        var sources = page.stream().limit(limit).toList();
        var replicas = sources.isEmpty() ? java.util.Map.<Long, Replica>of()
                : client.select(sources.stream().map(Source::jobId).toList(), claim.leaseId);
        if (replicas.values().stream().anyMatch(replica -> replica.fingerprint() == null))
            throw new PlanQueryException(503, "SCHEDULER_PROTOCOL_ERROR", "调度副本未提供配置指纹");
        Instant checked = mapper.now();
        var checks = sources.stream().map(source -> {
            var replica = replicas.get(source.jobId());
            return new Check(UUID.randomUUID(), claim.leaseId, source, replica, source.difference(replica), null, checked, null);
        }).filter(check -> !"MATCH".equals(check.difference())).toList();
        boolean more = page.size() > limit;
        return new Batch(more ? Phase.ADMIN : Phase.SCHEDULER, more ? sources.getLast().jobId() : 0,
                null, sources.size(), checks, null);
    }

    private Batch scanScheduler(Claim claim) {
        var round = claim.round;
        var page = client.page(round.afterJobId(), round.schedulerUpperJobId(), automatic.batchSize(), claim.leaseId);
        var known = mapper.knownJobs(page.replicas().stream().map(Replica::jobId).toList());
        Instant checked = mapper.now();
        var orphans = page.replicas().stream().filter(replica -> !known.contains(replica.jobId()))
                .map(replica -> new Check(UUID.randomUUID(), claim.leaseId, null, replica, "ORPHAN", null, checked, null)).toList();
        long after = page.replicas().isEmpty() ? round.afterJobId() : page.replicas().getLast().jobId();
        return new Batch(page.hasMore() ? Phase.SCHEDULER : Phase.COMPLETED, after,
                page.upperJobId(), page.replicas().size(), orphans, null);
    }

    private void save(Claim claim, Batch batch) {
        var leaseUntil = mapper.lockGate(GATE);
        var now = mapper.now();
        if (!leaseUntil.isAfter(now) || !mapper.advanceRound(claim.round, claim.leaseId, batch.phase, batch.after,
                batch.schedulerUpper, batch.scanned, batch.checks.size(), batch.error, now)) return;
        if (!batch.checks.isEmpty()) {
            mapper.insertScan(new Scan(claim.leaseId, claim.round.afterJobId(), automatic.batchSize(),
                    batch.after, false, "COMPLETED", "system", claim.started, leaseUntil, now, List.of()));
            mapper.insertChecks(claim.round.id(), batch.checks);
        }
        var interval = batch.error != null ? automatic.retryInterval()
                : batch.phase == Phase.COMPLETED ? automatic.roundInterval() : automatic.tickInterval();
        mapper.gate(GATE, now.plus(interval));
    }

    @Override
    public AutomaticStatus selectLatest() {
        return new AutomaticStatus(automatic.enabled(), mapper.latestRound().orElse(null));
    }

    @Override
    public DifferencePage selectDifferences(UUID id, long afterJobId, int batchSize) {
        if (id == null || afterJobId < 0 || batchSize < 1 || batchSize > 100)
            throw JobException.invalid("轮次 ID 必填，游标非负，每批 1 到 100 条");
        var rows = mapper.roundChecks(id, afterJobId, batchSize + 1);
        return new DifferencePage(rows.stream().limit(batchSize).toList(),
                rows.size() > batchSize ? rows.get(batchSize - 1).jobId() : null);
    }

    private record Claim(Round round, UUID leaseId, Instant started) { }
    private record Batch(Phase phase, long after, Long schedulerUpper, int scanned, List<Check> checks, String error) { }
}

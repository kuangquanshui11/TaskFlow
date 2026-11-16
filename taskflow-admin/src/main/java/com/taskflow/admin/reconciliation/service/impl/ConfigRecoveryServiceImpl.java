package com.taskflow.admin.reconciliation.service.impl;

import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.jobsync.client.PlanSyncClient;
import com.taskflow.admin.jobsync.client.PlanSyncClient.DeliveryException;
import com.taskflow.admin.jobsync.config.JobSyncProperties;
import com.taskflow.admin.jobsync.exception.PlanQueryException;
import com.taskflow.admin.jobsync.mapper.JobSyncMapper;
import com.taskflow.admin.reconciliation.mapper.ReconciliationMapper;
import com.taskflow.admin.reconciliation.model.ConfigRecoveryRecords.*;
import com.taskflow.admin.reconciliation.service.IConfigRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class ConfigRecoveryServiceImpl implements IConfigRecoveryService {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigRecoveryServiceImpl.class);
    private final boolean enabled;
    private final ReconciliationMapper reconciliation;
    private final JobSyncMapper outbox;
    private final PlanSyncClient client;

    public ConfigRecoveryServiceImpl(@Value("${taskflow.reconciliation.recovery.enabled:false}") boolean enabled,
                                     JobSyncProperties sync, ReconciliationMapper reconciliation, JobSyncMapper outbox,
                                     PlanSyncClient client) {
        if (enabled && sync.enabled()) throw new IllegalStateException("配置恢复期间必须暂停 Job 配置 Relay");
        this.enabled = enabled;
        this.reconciliation = reconciliation;
        this.outbox = outbox;
        this.client = client;
    }

    @Override
    public Bounds selectBounds() {
        requireRecovery();
        return new Bounds(reconciliation.sourceUpperBound());
    }

    @Override
    public Batch restore(long afterJobId, long upperJobId, int batchSize) {
        requireRecovery();
        if (afterJobId < 0 || upperJobId < afterJobId || batchSize < 1 || batchSize > 10)
            throw JobException.invalid("恢复游标无效，每批 1 到 10 条");
        if (upperJobId != reconciliation.sourceUpperBound())
            throw new PlanQueryException(409, "RECOVERY_BOUND_CHANGED", "Admin 配置范围发生变化，请核实写入已冻结后从头恢复");
        var page = outbox.recoveryEvents(afterJobId, upperJobId, batchSize + 1);
        var selected = page.stream().limit(batchSize).toList();
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        long last = afterJobId;
        for (var event : selected) {
            try {
                client.restore(event);
            } catch (DeliveryException failure) {
                LOG.warn("Config recovery stopped. actor={} jobId={} eventId={} version={} code={}", actor, event.jobId(),
                        event.eventId(), event.configVersion(), failure.getMessage());
                int status = failure.getMessage().equals("SCHEDULER_HTTP_409") ? 409 : 503;
                throw new PlanQueryException(status, "RECOVERY_BATCH_FAILED",
                        "恢复在任务 #" + event.jobId() + " 中止（" + failure.getMessage() + "）。排查后可使用原分页参数重试。");
            }
            last = event.jobId();
        }
        LOG.info("Config recovery batch completed. actor={} afterJobId={} lastJobId={} count={}",
                actor, afterJobId, last, selected.size());
        return new Batch(last, page.size() > batchSize, selected.size());
    }

    private void requireRecovery() {
        if (!enabled) throw new PlanQueryException(409, "CONFIG_RECOVERY_DISABLED", "Admin 未进入配置恢复模式");
    }
}

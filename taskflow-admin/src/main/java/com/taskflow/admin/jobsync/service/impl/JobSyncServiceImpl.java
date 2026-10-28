package com.taskflow.admin.jobsync.service.impl;

import java.time.Instant;
import java.util.UUID;
import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.jobsync.client.PlanSyncClient;
import com.taskflow.admin.jobsync.client.PlanSyncClient.DeliveryException;
import com.taskflow.admin.jobsync.config.JobSyncProperties;
import com.taskflow.admin.jobsync.mapper.JobSyncMapper;
import com.taskflow.admin.jobsync.model.JobSyncRecords.*;
import com.taskflow.admin.jobsync.model.PlanView;
import com.taskflow.admin.jobsync.service.IJobSyncService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobSyncServiceImpl implements IJobSyncService {
    private final JobSyncMapper mapper;
    private final PlanSyncClient client;
    private final JobSyncProperties properties;

    public JobSyncServiceImpl(JobSyncMapper mapper, PlanSyncClient client, JobSyncProperties properties) {
        this.mapper = mapper; this.client = client; this.properties = properties;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(JobDefinition job, boolean deleted) {
        mapper.insert(new Event(UUID.randomUUID(), job.id(), job.configVersion(), deleted, job.configuration()));
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void dispatchPending() {
        for (var id : mapper.due(Instant.now(), properties.batchSize())) {
            if (Thread.currentThread().isInterrupted()) return;
            var now = Instant.now();
            var claimed = mapper.claim(id, UUID.randomUUID(), now, now.plus(properties.claimLease()), properties.maxAttempts());
            if (claimed.isEmpty()) continue;
            var delivery = claimed.get();
            try { mapper.acknowledge(delivery, client.send(delivery.event()), Instant.now()); }
            catch (DeliveryException error) {
                var failedAt = Instant.now();
                var delay = properties.retryDelay().multipliedBy(1L << (delivery.attempts() - 1));
                if (delay.compareTo(properties.maxRetryDelay()) > 0) delay = properties.maxRetryDelay();
                mapper.fail(delivery, error.getMessage(), error.retryable() && delivery.attempts() < properties.maxAttempts(),
                        failedAt.plus(delay), failedAt);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Status selectByJobId(long jobId) {
        return mapper.latest(jobId).orElseThrow(() -> new JobException(JobException.Kind.NOT_FOUND, "任务同步记录不存在"));
    }

    @Override
    @Transactional
    public Status retry(long jobId, long configVersion) {
        mapper.lockJob(jobId);
        var status = selectByJobId(jobId);
        if (status.configVersion() != configVersion || !mapper.retry(jobId, configVersion))
            throw new JobException(JobException.Kind.CONFLICT, "仅可重试最新配置的失败同步，请刷新同步状态");
        return selectByJobId(jobId);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PlanView selectPlanByJobId(long jobId) {
        if (jobId < 1) throw JobException.invalid("id 必须为正数");
        return client.selectPlan(jobId);
    }
}

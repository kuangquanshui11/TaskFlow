package com.taskflow.admin.job.service.impl;

import java.time.Instant;

import com.taskflow.admin.job.mapper.JobCacheMapper;
import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.service.IJobDetailService;
import com.taskflow.admin.job.service.IJobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class JobDetailServiceImpl implements IJobDetailService {
    private final JobCacheMapper cache;
    private final IJobService jobs;

    public JobDetailServiceImpl(JobCacheMapper cache, IJobService jobs) {
        this.cache = cache;
        this.jobs = jobs;
    }

    @Override
    public JobDefinition selectById(long id) {
        // 将来若被其他事务用例复用，不能读取缓存或将未提交的数据回填出去。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return jobs.selectById(id);
        }
        var cached = cache.find(id);
        if (cached.isPresent()) {
            return cached.get();
        }
        // TTL 从数据库查询开始计算，延迟回填不能给旧快照重新续满有效期。
        var readStartedAt = Instant.now();
        var job = jobs.selectById(id);
        // 此处数据库只读事务已经结束，缓存 I/O 不占用数据库事务。
        cache.store(job, readStartedAt);
        return job;
    }
}

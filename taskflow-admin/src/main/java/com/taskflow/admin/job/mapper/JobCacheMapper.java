package com.taskflow.admin.job.mapper;

import java.time.Instant;
import java.util.Optional;

import com.taskflow.admin.job.model.JobDefinition;

/** 可丢弃的展示数据；实现必须将缓存故障降级，不能影响数据库用例。 */
public interface JobCacheMapper {
    Optional<JobDefinition> find(long id);

    void store(JobDefinition job, Instant readStartedAt);
}

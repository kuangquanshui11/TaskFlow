package com.taskflow.admin.job.mapper;

import java.util.List;
import java.util.Optional;

import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.model.JobFilter;
import com.taskflow.admin.job.model.JobSummary;

public interface JobDataMapper {
    long insert(JobDefinition.Configuration configuration);
    Optional<JobDefinition> findById(long id);
    List<JobDefinition> select(JobFilter filter);
    long count(JobFilter filter);
    JobSummary summarize();
    boolean update(long id, long configVersion, JobDefinition.Configuration configuration);
    boolean changeEnabled(long id, long configVersion, boolean enabled);
    boolean softDelete(long id, long configVersion);
}

package com.taskflow.admin.job.service;

import java.util.List;

import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import com.taskflow.admin.job.model.JobFilter;
import com.taskflow.admin.job.model.JobSummary;

public interface IJobService {
    JobDefinition insert(Configuration input);
    JobDefinition selectById(long id);
    Page select(JobFilter filter);
    JobSummary selectSummary();
    JobDefinition update(long id, long configVersion, Configuration input);
    JobDefinition changeEnabled(long id, long configVersion, boolean enabled);
    void delete(long id, long configVersion);

    record Page(List<JobDefinition> records, long total, int page, int pageSize) {
    }
}

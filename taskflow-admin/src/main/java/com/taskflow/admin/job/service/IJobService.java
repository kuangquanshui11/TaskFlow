package com.taskflow.admin.job.service;

import java.util.List;

import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import com.taskflow.admin.job.model.JobFilter;
import com.taskflow.admin.job.model.JobSummary;

public interface IJobService {
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('job:write')")
    JobDefinition insert(Configuration input);
    JobDefinition selectById(long id);
    Page select(JobFilter filter);
    JobSummary selectSummary();
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('job:write')")
    JobDefinition update(long id, long configVersion, Configuration input);
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('job:operate')")
    JobDefinition changeEnabled(long id, long configVersion, boolean enabled);
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('job:delete')")
    void delete(long id, long configVersion);

    record Page(List<JobDefinition> records, long total, int page, int pageSize) {
    }
}

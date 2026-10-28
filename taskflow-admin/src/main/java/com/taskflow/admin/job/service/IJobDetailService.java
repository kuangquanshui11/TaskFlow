package com.taskflow.admin.job.service;

import com.taskflow.admin.job.model.JobDefinition;

public interface IJobDetailService {
    JobDefinition selectById(long id);
}

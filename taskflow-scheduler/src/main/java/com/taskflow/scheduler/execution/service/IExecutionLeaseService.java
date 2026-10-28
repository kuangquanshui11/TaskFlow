package com.taskflow.scheduler.execution.service;

import com.taskflow.scheduler.execution.dto.ExecutionProtocol.LeaseRequest;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.LeaseAcknowledgement;

public interface IExecutionLeaseService {
    LeaseAcknowledgement updateLease(LeaseRequest request);
}

package com.taskflow.admin.node.service;

import com.taskflow.admin.node.dto.WorkerNodeResponse.*;

public interface IWorkerNodeService {
    Page select(String keyword,String state,int page,int pageSize);
    View selectById(String workerId);
}

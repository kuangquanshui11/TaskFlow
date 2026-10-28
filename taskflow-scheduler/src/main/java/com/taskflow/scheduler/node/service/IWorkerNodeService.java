package com.taskflow.scheduler.node.service;

import com.taskflow.scheduler.node.dto.WorkerNodeProtocol.*;

public interface IWorkerNodeService {
    Acknowledgement register(Registration request);
    Acknowledgement heartbeat(Heartbeat request);
    Page select(String keyword,String state,int page,int pageSize);
    View selectById(String workerId);
    boolean canDispatch(String handlerKey);
}

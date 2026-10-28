package com.taskflow.admin.node.service.impl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.taskflow.admin.node.client.WorkerNodeClient;
import com.taskflow.admin.node.dto.WorkerNodeResponse.*;
import com.taskflow.admin.node.exception.WorkerNodeException;
import com.taskflow.admin.node.service.IWorkerNodeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class WorkerNodeServiceImpl implements IWorkerNodeService {
    private final WorkerNodeClient client;
    public WorkerNodeServiceImpl(WorkerNodeClient client) { this.client=client; }
    @Override @Transactional(propagation=Propagation.NEVER)
    public Page select(String keyword,String state,int page,int size) {
        if (keyword==null || keyword.length()>100 || !java.util.Set.of("ALL","ONLINE","OFFLINE").contains(state)
                || page<1 || page>1000000 || size<1 || size>100) throw invalid();
        try { return client.select("keyword="+encode(keyword.trim())+"&state="+state+"&page="+page+"&pageSize="+size,page,size); }
        catch (tools.jackson.core.JacksonException | NullPointerException malformed) { throw new WorkerNodeException(503,"SCHEDULER_PROTOCOL_ERROR","调度服务返回的节点格式不合法"); }
    }
    @Override @Transactional(propagation=Propagation.NEVER)
    public View selectById(String id) {
        if (id==null || !id.matches("[a-z0-9][a-z0-9_.-]{0,99}")) throw invalid();
        try { return client.selectById("workerId="+encode(id),id); }
        catch (tools.jackson.core.JacksonException | NullPointerException malformed) { throw new WorkerNodeException(503,"SCHEDULER_PROTOCOL_ERROR","调度服务返回的节点格式不合法"); }
    }
    private String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private WorkerNodeException invalid() { return new WorkerNodeException(400,"INVALID_ARGUMENT","节点查询参数不合法"); }
}

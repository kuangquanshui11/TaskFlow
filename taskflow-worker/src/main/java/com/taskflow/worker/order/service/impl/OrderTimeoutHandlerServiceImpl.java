package com.taskflow.worker.order.service.impl;

import java.util.Set;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.exception.HandlerFailureException;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.execution.service.ITaskHandler;
import com.taskflow.worker.order.config.OrderHandlerProperties;
import com.taskflow.worker.remote.client.OrderTimeoutRemote;
import com.taskflow.worker.remote.model.RemoteErrors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@EnableConfigurationProperties(OrderHandlerProperties.class)
@ConditionalOnProperty(name="taskflow.order.enabled",havingValue="true")
public class OrderTimeoutHandlerServiceImpl implements ITaskHandler {
    private final OrderTimeoutRemote remote;
    private final OrderHandlerProperties properties;
    public OrderTimeoutHandlerServiceImpl(OrderTimeoutRemote remote,OrderHandlerProperties properties) { this.remote=remote;this.properties=properties; }
    @Override public Set<String> keys() { return Set.of("orderTimeoutCheck"); }
    private int parameter(JsonNode payload,String key,int fallback,int max) {
        if(payload==null || !payload.has(key)) return fallback;
        var value=payload.get(key);
        if(!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<1 || value.intValue()>max)
            throw new IllegalArgumentException("Invalid "+key);
        return value.intValue();
    }
    @Override public String execute(Snapshot snapshot,ExecutionContext context) {
        var payload=snapshot.payload();
        if(payload!=null && payload.has("timeoutMinutes")) throw new IllegalArgumentException("Expiry belongs to order service");
        int size=parameter(payload,"batchSize",100,500), max=parameter(payload,"maxBatches",10,100);
        if(properties.token()==null || properties.token().isBlank()) throw new HandlerFailureException("订单服务鉴权未配置",false);
        int scanned=0,closed=0,skipped=0,failed=0,batches=0;
        var next=new OrderTimeoutRemote.Batch(size,null,null,null);
        for(int i=0;i<max;i++) {
            context.checkActive();
            // 不启动无法放进剩余执行预算的请求，网络超时不会延长本地资格。
            if(java.time.Instant.now().plus(properties.connectTimeout()).plus(properties.requestTimeout()).isAfter(context.executionDeadline())) break;
            OrderTimeoutRemote.Result result;
            batches++;
            try { result=remote.close(next,RemoteErrors.headers(properties.token(),context.businessIdempotencyKey("close-"+i))); }
            catch(RuntimeException failure) {
                int status=RemoteErrors.status(failure);
                throw new HandlerFailureException("订单服务调用失败，HTTP="+status,status==408 || status==429 || status>=500);
            }
            context.checkActive();
            if(result==null || result.cutoff()==null || result.scanned()<0 || result.scanned()>size
                    || result.closed()<0 || result.skipped()<0 || result.failed()<0
                    || result.closed()+result.skipped()+result.failed()!=result.scanned()
                    || result.scanned()>0 && (result.afterId()==null || result.afterExpiresAt()==null
                    || result.afterId().equals(next.afterId()))) throw new HandlerFailureException("订单服务响应不合法",false);
            scanned+=result.scanned();closed+=result.closed();skipped+=result.skipped();failed+=result.failed();
            if(result.scanned()<size) break;
            next=new OrderTimeoutRemote.Batch(size,result.cutoff(),result.afterExpiresAt(),result.afterId());
        }
        if(batches==0) throw new HandlerFailureException("剩余执行预算不足，未发起订单请求",true);
        String summary="scanned="+scanned+", closed="+closed+", skipped="+skipped+", failed="+failed;
        if(failed>0) throw new HandlerFailureException(summary,true);
        return summary;
    }
}

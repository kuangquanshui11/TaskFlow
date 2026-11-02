package com.taskflow.worker.sales.service.impl;

import java.time.*;
import java.util.Set;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.execution.service.ITaskHandler;
import com.taskflow.worker.execution.exception.HandlerFailureException;
import com.taskflow.worker.order.config.OrderHandlerProperties;
import com.taskflow.worker.remote.client.SalesReportRemote;
import com.taskflow.worker.remote.model.RemoteErrors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="taskflow.sales.enabled",havingValue="true")
public class SalesReportHandlerServiceImpl implements ITaskHandler {
    private final SalesReportRemote remote;
    private final OrderHandlerProperties properties;
    public SalesReportHandlerServiceImpl(SalesReportRemote remote,OrderHandlerProperties properties) {
        this.remote=remote; this.properties=properties;
        if(properties.token()==null || properties.token().isBlank()) throw new IllegalStateException("Sales report token required");
    }
    @Override public Set<String> keys() { return Set.of("salesReport"); }
    @Override public String execute(Snapshot snapshot,ExecutionContext context) {
        var payload=snapshot.payload();
        if(payload==null || !payload.isObject() || !payload.has("shopId") || !payload.get("shopId").isIntegralNumber()
                || !payload.get("shopId").canConvertToLong() || payload.get("shopId").longValue()<1) throw new IllegalArgumentException("shopId required");
        LocalDate date=null;
        if(payload.has("businessDate")) {
            if(!payload.get("businessDate").isTextual()) throw new IllegalArgumentException("businessDate must be a date");
            try { date=LocalDate.parse(payload.get("businessDate").stringValue()); }
            catch(DateTimeException invalid) { throw new IllegalArgumentException("businessDate must be ISO date",invalid); }
        }
        var zone=ZoneId.of(snapshot.timeZone());
        var input=new SalesReportRemote.Generate(context.businessIdempotencyKey("sales-report"),payload.get("shopId").longValue(),date,zone.getId());
        context.checkActive();
        if(Instant.now().plus(properties.connectTimeout()).plus(properties.requestTimeout()).isAfter(context.executionDeadline()))
            throw new HandlerFailureException("报表请求剩余执行预算不足",true);
        SalesReportRemote.Report report;
        try { report=remote.insert(input,RemoteErrors.headers(properties.token(),input.requestKey())); }
        catch(RuntimeException failure) {
            int status=RemoteErrors.status(failure);
            throw new HandlerFailureException("报表服务调用失败，HTTP="+status,status==408 || status==429 || status>=500);
        }
        context.checkActive();
        if(report==null || report.id()==null || !report.id().matches("[a-f0-9-]{36}") || report.shopId()!=input.shopId()
                || report.businessDate()==null || date!=null && !date.equals(report.businessDate())
                || !zone.getId().equals(report.timeZone()) || report.orderCount()<0 || report.totalQuantity()<0
                || report.totalAmountCents()<0 || report.generatedAt()==null)
            throw new HandlerFailureException("报表服务响应不合法",false);
        return "reportId="+report.id()+", date="+report.businessDate()+", orders="+report.orderCount()
                +", quantity="+report.totalQuantity()+", amountCents="+report.totalAmountCents();
    }
}

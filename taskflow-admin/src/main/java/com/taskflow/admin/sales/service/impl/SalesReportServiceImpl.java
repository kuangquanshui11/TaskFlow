package com.taskflow.admin.sales.service.impl;

import java.util.*;
import java.util.function.Supplier;
import com.taskflow.admin.sales.config.SalesClientProperties;
import com.taskflow.admin.sales.dto.SalesResponse.*;
import com.taskflow.admin.sales.exception.SalesReportException;
import com.taskflow.admin.sales.service.ISalesReportService;
import com.taskflow.admin.remote.client.SalesReportRemote;
import com.taskflow.admin.remote.model.RemoteErrors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@EnableConfigurationProperties(SalesClientProperties.class)
@Transactional(propagation=Propagation.NEVER)
public class SalesReportServiceImpl implements ISalesReportService {
    private final SalesReportRemote remote;
    private final SalesClientProperties properties;
    public SalesReportServiceImpl(SalesReportRemote remote,SalesClientProperties properties) { this.remote=remote; this.properties=properties; }
    private Map<String,String> headers() {
        if(properties.token()==null || properties.token().isBlank()) throw new SalesReportException(503,"报表服务尚未接入");
        return RemoteErrors.headers(properties.token(),UUID.randomUUID().toString());
    }
    private <T> T call(Supplier<T> operation) {
        try { var result=operation.get(); if(result==null) throw new IllegalStateException("Empty response"); return result; }
        catch(SalesReportException expected) { throw expected; }
        catch(RuntimeException failure) {
            int status=RemoteErrors.status(failure);
            throw new SalesReportException(status==404?404:503,status==404?"报表不存在":"报表服务暂时不可用，请稍后重试");
        }
    }
    private void id(String value) {
        if(value==null || !value.matches("[a-f0-9-]{36}")) throw new SalesReportException(400,"报表编号不合法");
    }
    private SalesReportException malformed() { return new SalesReportException(503,"报表服务返回的数据不完整，请稍后重试"); }
    private void valid(Report report) {
        if(report==null || report.id()==null || !report.id().matches("[a-f0-9-]{36}") || report.shopId()<1
                || report.businessDate()==null || report.timeZone()==null || report.timeZone().isBlank()
                || report.generatedAt()==null || report.orderCount()<0 || report.totalQuantity()<0
                || report.totalAmountCents()<0) throw malformed();
    }
    @Override public Page select(Long shopId,int page,int size) {
        if(shopId!=null && shopId<1 || page<1 || page>1000000 || size<1 || size>100) throw new SalesReportException(400,"报表查询参数不合法");
        var query=new HashMap<String,String>(); query.put("page",Integer.toString(page)); query.put("pageSize",Integer.toString(size));
        if(shopId!=null) query.put("shopId",shopId.toString());
        var result=call(()->remote.select(query,headers()));
        if(result.items()==null || result.total()<0 || result.page()!=page || result.pageSize()!=size
                || result.items().size()>size || result.total()<result.items().size()) throw malformed();
        for(var report:result.items()) {
            valid(report);
            if(shopId!=null && report.shopId()!=shopId) throw malformed();
        }
        return result;
    }
    @Override public Detail selectById(String value) {
        id(value); var result=call(()->remote.detail(value,headers())); valid(result.report());
        if(!value.equals(result.report().id()) || result.items()==null) throw malformed();
        long count=0,quantity=0,amount=0;
        var seen=new HashSet<Long>();
        try {
            for(var item:result.items()) {
                if(item==null || item.skuId()<1 || item.productName()==null || !seen.add(item.skuId())
                        || item.orderCount()<0 || item.totalQuantity()<0 || item.totalAmountCents()<0) throw malformed();
                count=Math.addExact(count,item.orderCount()); quantity=Math.addExact(quantity,item.totalQuantity());
                amount=Math.addExact(amount,item.totalAmountCents());
            }
        } catch(ArithmeticException overflow) { throw malformed(); }
        if(count!=result.report().orderCount() || quantity!=result.report().totalQuantity()
                || amount!=result.report().totalAmountCents()) throw malformed();
        return result;
    }
    @Override public Export selectExport(String value) {
        id(value); var result=call(()->remote.export(value,headers()));
        if(result.filename()==null || !result.filename().matches("sales-[0-9]+-[0-9-]+\\.csv")
                || result.content()==null) throw malformed();
        return result;
    }
}

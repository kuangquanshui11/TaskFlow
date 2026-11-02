package com.taskflow.orders.order.service.impl;

import java.time.Instant;
import java.util.UUID;
import com.taskflow.orders.config.OrderProperties;
import com.taskflow.orders.inventory.service.IInventoryService;
import com.taskflow.orders.order.dto.OrderProtocol.*;
import com.taskflow.orders.order.mapper.OrderMapper;
import com.taskflow.orders.order.service.IOrderService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderServiceImpl implements IOrderService {
    private final OrderMapper mapper;
    private final IInventoryService inventory;
    private final OrderProperties properties;
    private final TransactionTemplate transactions;
    private final com.taskflow.orders.report.mapper.SalesMapper sales;
    public OrderServiceImpl(OrderMapper mapper,IInventoryService inventory,OrderProperties properties,PlatformTransactionManager manager,
                            com.taskflow.orders.report.mapper.SalesMapper sales) {
        this.sales=sales;
        this.mapper=mapper;this.inventory=inventory;this.properties=properties;transactions=new TransactionTemplate(manager);
    }
    @Override public Order insert(Create request) {
        if(request.requestKey()==null || request.requestKey().isBlank() || request.requestKey().length()>100
                || request.skuId()<=0 || request.quantity()<=0 || request.quantity()>1000000) throw new IllegalArgumentException("Invalid order");
        var existing=mapper.byKey(request.requestKey());
        if(existing!=null) return same(existing,request);
        try {
            return transactions.execute(status -> {
                var now=Instant.now();
                var order=new Order(UUID.randomUUID().toString(),request.requestKey(),request.skuId(),request.quantity(),
                        "PENDING",now,now.plus(properties.paymentWindow()),null,null,null);
                // 先插入唯一请求键，竞争请求在扣库存之前等待；异常必须退出整个事务后再查询获胜者。
                mapper.insert(order);inventory.hold(order.id(),order.skuId(),order.quantity(),request.requestKey());
                var product=sales.product(order.skuId());
                if(product!=null) sales.snapshot(order.id(),product,Math.multiplyExact(product.unitPriceCents(),order.quantity()));
                return mapper.find(order.id());
            });
        } catch(DuplicateKeyException duplicate) {
            var winner=mapper.byKey(request.requestKey());
            if(winner==null) throw duplicate;
            return same(winner,request);
        }
    }
    private Order same(Order order,Create request) {
        if(order.skuId()!=request.skuId() || order.quantity()!=request.quantity())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
        return order;
    }
    private Order require(Order order) {
        if(order==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ORDER_NOT_FOUND");
        return order;
    }
    @Override public Detail select(String id) { return new Detail(require(mapper.find(id)),mapper.ledger(id)); }
    @Override public Order updatePayment(String id) {
        return transactions.execute(status -> {
            var order=require(mapper.lock(id)); var now=Instant.now();
            if(order.state().equals("PAID")) return order;
            if(!order.state().equals("PENDING") || !now.isBefore(order.expiresAt()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"ORDER_CLOSED_OR_EXPIRED");
            inventory.settle(id,order.skuId(),order.quantity(),false,"simulation-payment");
            if(mapper.pay(id,now)!=1) throw new IllegalStateException("Order changed under lock");
            return mapper.find(id);
        });
    }
    @Override public BatchResult updateTimeout(Batch input,String requestId) {
        var now=Instant.now();
        if(input.batchSize()<1 || input.batchSize()>500 || (input.afterId()==null)!=(input.afterExpiresAt()==null)
                || input.cutoff()!=null && input.cutoff().isAfter(now)
                || input.afterId()!=null && input.cutoff()==null
                || requestId==null || requestId.isBlank() || requestId.length()>128)
            throw new IllegalArgumentException("Invalid batch/correlation parameters");
        Instant cutoff=input.cutoff()==null?now:input.cutoff();
        var batch=new Batch(input.batchSize(),cutoff,input.afterExpiresAt(),input.afterId());
        var candidates=mapper.candidates(batch);
        int closed=0,skipped=0,failed=0;
        for(var candidate:candidates) {
            try {
                boolean changed=Boolean.TRUE.equals(transactions.execute(status -> {
                    var order=require(mapper.lock(candidate.id()));
                    if(!order.state().equals("PENDING") || order.expiresAt().isAfter(cutoff)) return false;
                    inventory.settle(order.id(),order.skuId(),order.quantity(),true,requestId);
                    if(mapper.close(order.id(),Instant.now())!=1) throw new IllegalStateException("Order changed under lock");
                    return true;
                }));
                if(changed) closed++; else skipped++;
            } catch(RuntimeException failure) {
                failed++;
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Timeout close failed: orderId={}, type={}",candidate.id(),failure.getClass().getSimpleName());
            }
        }
        var last=candidates.isEmpty()?null:candidates.getLast();
        return new BatchResult(candidates.size(),closed,skipped,failed,cutoff,
                last==null?input.afterExpiresAt():last.expiresAt(),last==null?input.afterId():last.id());
    }
}

package com.taskflow.orders.sample.service.impl;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.taskflow.orders.report.dto.SalesProtocol.*;
import com.taskflow.orders.report.mapper.SalesMapper;
import com.taskflow.orders.sample.mapper.SalesSampleMapper;
import com.taskflow.orders.sample.service.ISalesSampleService;
import com.taskflow.orders.order.dto.OrderProtocol.Create;
import com.taskflow.orders.order.mapper.OrderMapper;
import com.taskflow.orders.order.service.IOrderService;
import com.taskflow.orders.inventory.service.IInventoryService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SalesSampleServiceImpl implements ISalesSampleService {
    private final SalesSampleMapper samples;
    private final SalesMapper sales;
    private final IOrderService orders;
    private final OrderMapper orderMapper;
    private final IInventoryService inventory;
    private final TransactionTemplate transactions;
    public SalesSampleServiceImpl(SalesSampleMapper samples,SalesMapper sales,IOrderService orders,
            OrderMapper orderMapper,IInventoryService inventory,PlatformTransactionManager manager) {
        this.samples=samples; this.sales=sales; this.orders=orders; this.orderMapper=orderMapper;
        this.inventory=inventory; this.transactions=new TransactionTemplate(manager);
    }
    @Override public Sample insert(Sample sample) {
        if(sample.dataset()==null || !sample.dataset().matches("[a-z][a-z0-9-]{0,31}") || sample.startDate()==null
                || sample.days()<1 || sample.days()>90 || sample.orderCount()<1 || sample.orderCount()>10000
                || sample.startDate().plusDays(sample.days()).isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw new IllegalArgumentException();
        var saved=samples.find(sample.dataset());
        if(saved!=null) return same(saved,sample);
        try {
            return transactions.execute(status -> {
                samples.insert(sample);
                var random=new Random(sample.seed());
                long skuBase=(Integer.toUnsignedLong(sample.dataset().hashCode())+1)*100;
                var products=new ArrayList<Product>();
                for(int shop=8;shop<=10;shop++) {
                    if(sales.shop(shop)==null) samples.shop(shop,"样例店铺 "+shop);
                    // 锁定已有店铺，保证本批数据与并行生成的日报不会交叉提交。
                    sales.lockShop(shop);
                    for(int n=0;n<10;n++) {
                        var product=new Product(skuBase+products.size(),shop,"样例商品 "+(n+1),500+random.nextInt(49501));
                        samples.product(product); inventory.insert(product.skuId(),100000); products.add(product);
                    }
                }
                var zone=ZoneId.of("Asia/Shanghai");
                for(int n=0;n<sample.orderCount();n++) {
                    var product=products.get(random.nextInt(products.size()));
                    var key="sales-sample:"+sample.dataset()+":"+n;
                    var order=orders.insert(new Create(key,product.skuId(),1+random.nextInt(5)));
                    var created=sample.startDate().plusDays(random.nextInt(sample.days())).atStartOfDay(zone)
                            .plusSeconds(random.nextInt(23*3600)).toInstant();
                    int state=random.nextInt(10);
                    Instant paid=null,closed=null;
                    if(state<7) { orders.updatePayment(order.id()); paid=created.plusSeconds(60); }
                    else if(state<9) {
                        inventory.settle(order.id(),order.skuId(),order.quantity(),true,key);
                        if(orderMapper.close(order.id(),Instant.now())!=1) throw new IllegalStateException();
                        closed=created.plusSeconds(1800);
                    }
                    samples.dates(order.id(),created,created.plusSeconds(1800),paid,closed);
                    samples.ledgerDates(order.id(),created,paid,closed);
                }
                return sample;
            });
        } catch(DuplicateKeyException duplicate) {
            var winner=samples.find(sample.dataset());
            if(winner==null) throw duplicate;
            return same(winner,sample);
        }
    }
    private Sample same(Sample old,Sample next) {
        if(!old.equals(next)) throw new ResponseStatusException(HttpStatus.CONFLICT,"DATASET_CONFLICT");
        return old;
    }
}

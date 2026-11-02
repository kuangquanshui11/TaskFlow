package com.taskflow.orders.report.service.impl;

import java.time.*;
import java.util.*;
import com.taskflow.orders.report.dto.SalesProtocol.*;
import com.taskflow.orders.report.mapper.SalesMapper;
import com.taskflow.orders.report.service.ISalesReportService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SalesReportServiceImpl implements ISalesReportService {
    private final SalesMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    public SalesReportServiceImpl(SalesMapper mapper, PlatformTransactionManager manager,
            @org.springframework.beans.factory.annotation.Qualifier("salesClock") Clock clock) {
        this.clock=clock;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
    }
    @Override public Report insert(Generate input) {
        if (input.requestKey()==null || input.requestKey().isBlank() || input.requestKey().length()>100
                || input.shopId()<1 || input.timeZone()==null || input.timeZone().length()>100) throw new IllegalArgumentException();
        var zone = ZoneId.of(input.timeZone());
        if (mapper.shop(input.shopId())==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SHOP_NOT_FOUND");
        // 日期绑定独立提交；统计失败或跨天重试不能改变同一 Run 的目标日期。
        var binding = mapper.request(input.requestKey());
        if (binding==null) {
            var today=LocalDate.now(clock.withZone(zone));
            var date = input.businessDate()==null ? today.minusDays(1) : input.businessDate();
            if (!date.isBefore(today)) throw new IllegalArgumentException("Only completed business days");
            var candidate = new Request(input.requestKey(),input.shopId(),input.businessDate(),date,zone.getId());
            try { transactions.executeWithoutResult(status -> mapper.insertRequest(candidate)); }
            catch (DuplicateKeyException competing) { /* 获胜者的事务完成后读取其固定日期。 */ }
            binding = mapper.request(input.requestKey());
        }
        if (binding==null) throw new IllegalStateException("Missing request binding");
        if (binding.shopId()!=input.shopId() || !Objects.equals(binding.requestedDate(),input.businessDate())
                || !binding.timeZone().equals(zone.getId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
        var request = binding;
        return transactions.execute(status -> {
            // 同店铺的生成请求串行；报表头和全部明细在同一事务提交。
            if (mapper.lockShop(request.shopId())==null) throw new IllegalStateException("Shop disappeared");
            var saved = mapper.byScope(request.shopId(),request.businessDate(),request.timeZone());
            if (saved!=null) return saved;
            var start = request.businessDate().atStartOfDay(zone).toInstant();
            var end = request.businessDate().plusDays(1).atStartOfDay(zone).toInstant();
            var items = mapper.aggregate(request.shopId(),start,end);
            long count=0, quantity=0, amount=0;
            for (var item:items) {
                count=Math.addExact(count,item.orderCount()); quantity=Math.addExact(quantity,item.totalQuantity());
                amount=Math.addExact(amount,item.totalAmountCents());
            }
            var report = new Report(UUID.randomUUID().toString(),request.shopId(),request.businessDate(),
                    request.timeZone(),count,quantity,amount,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            mapper.insertReport(report);
            for (var item:items) mapper.insertItem(report.id(),item);
            return report;
        });
    }
    @Override public Page select(Long shopId,int page,int size) {
        if (shopId!=null && shopId<1 || page<1 || page>1000000 || size<1 || size>100) throw new IllegalArgumentException();
        return new Page(mapper.list(shopId,(page-1)*size,size),mapper.count(shopId),page,size);
    }
    @Override public Detail selectById(String id) {
        if (id==null || !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException();
        var report=mapper.report(id);
        if (report==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND");
        return new Detail(report,mapper.items(id));
    }
    @Override public Export selectExport(String id) {
        var detail=selectById(id);
        var csv=new StringBuilder("\uFEFF店铺,统计日期,时区,商品编号,商品,付款订单数,销量,销售额（分）\r\n");
        for(var item:detail.items()) csv.append(detail.report().shopId()).append(',').append(detail.report().businessDate())
                .append(',').append(cell(detail.report().timeZone())).append(',').append(item.skuId()).append(',')
                .append(cell(item.productName())).append(',').append(item.orderCount()).append(',')
                .append(item.totalQuantity()).append(',').append(item.totalAmountCents()).append("\r\n");
        csv.append(detail.report().shopId()).append(',').append(detail.report().businessDate()).append(",,合计,,")
                .append(detail.report().orderCount()).append(',').append(detail.report().totalQuantity()).append(',')
                .append(detail.report().totalAmountCents()).append("\r\n");
        return new Export("sales-"+detail.report().shopId()+"-"+detail.report().businessDate()+".csv",csv.toString());
    }
    private String cell(String value) {
        var trimmed=value.stripLeading();
        if(!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0))>=0
                || !value.isEmpty() && "\t\r\n".indexOf(value.charAt(0))>=0) value="'"+value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}

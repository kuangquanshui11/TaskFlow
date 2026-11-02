package com.taskflow.admin.sales.dto;
import java.time.*;
import java.util.List;
public final class SalesResponse {
    private SalesResponse() { }
    public record Report(String id,long shopId,LocalDate businessDate,String timeZone,long orderCount,
                         long totalQuantity,long totalAmountCents,Instant generatedAt) { }
    public record Item(long skuId,String productName,long orderCount,long totalQuantity,long totalAmountCents) { }
    public record Page(List<Report> items,long total,int page,int pageSize) { }
    public record Detail(Report report,List<Item> items) { }
    public record Export(String filename,String content) { }
}

package com.taskflow.orders.report.mapper;

import java.time.Instant;
import java.util.List;
import com.taskflow.orders.report.dto.SalesProtocol.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SalesMapper {
    @Select("SELECT * FROM sales_product WHERE sku_id=#{skuId}") Product product(long skuId);
    @Insert("INSERT INTO order_sale_snapshot(order_id,shop_id,sku_id,product_name,unit_price_cents,total_amount_cents) VALUES(#{orderId},#{product.shopId},#{product.skuId},#{product.name},#{product.unitPriceCents},#{amount})")
    void snapshot(String orderId, Product product, long amount);
    @Select("SELECT id FROM sales_shop WHERE id=#{shopId} FOR UPDATE") Long lockShop(long shopId);
    @Select("SELECT id FROM sales_shop WHERE id=#{shopId}") Long shop(long shopId);
    @Insert("INSERT INTO sales_report_request(request_key,shop_id,requested_date,business_date,time_zone) VALUES(#{requestKey},#{shopId},#{requestedDate},#{businessDate},#{timeZone})")
    void insertRequest(Request request);
    @Select("SELECT * FROM sales_report_request WHERE request_key=#{key}") Request request(String key);
    @Select("SELECT * FROM sales_report WHERE shop_id=#{shopId} AND business_date=#{date} AND time_zone=#{zone}")
    Report byScope(long shopId, java.time.LocalDate date, String zone);
    @Select("SELECT * FROM sales_report WHERE id=#{id}") Report report(String id);
    @Select("""
        SELECT s.sku_id,s.product_name,COUNT(*) order_count,SUM(o.quantity) total_quantity,
               SUM(s.total_amount_cents) total_amount_cents
        FROM purchase_order o JOIN order_sale_snapshot s ON s.order_id=o.id
        WHERE s.shop_id=#{shopId} AND o.state='PAID' AND o.paid_at>=#{start} AND o.paid_at<#{end}
        GROUP BY s.sku_id,s.product_name ORDER BY s.sku_id
        """)
    List<Item> aggregate(long shopId, Instant start, Instant end);
    @Insert("INSERT INTO sales_report(id,shop_id,business_date,time_zone,order_count,total_quantity,total_amount_cents,generated_at) VALUES(#{id},#{shopId},#{businessDate},#{timeZone},#{orderCount},#{totalQuantity},#{totalAmountCents},#{generatedAt})")
    void insertReport(Report report);
    @Insert("INSERT INTO sales_report_item(report_id,sku_id,product_name,order_count,total_quantity,total_amount_cents) VALUES(#{reportId},#{item.skuId},#{item.productName},#{item.orderCount},#{item.totalQuantity},#{item.totalAmountCents})")
    void insertItem(String reportId, Item item);
    @Select("SELECT sku_id,product_name,order_count,total_quantity,total_amount_cents FROM sales_report_item WHERE report_id=#{id} ORDER BY sku_id")
    List<Item> items(String id);
    @Select("<script>SELECT * FROM sales_report <if test='shopId != null'>WHERE shop_id=#{shopId}</if> ORDER BY business_date DESC,shop_id,id LIMIT #{size} OFFSET #{offset}</script>")
    List<Report> list(Long shopId, int offset, int size);
    @Select("<script>SELECT COUNT(*) FROM sales_report <if test='shopId != null'>WHERE shop_id=#{shopId}</if></script>")
    long count(Long shopId);
}

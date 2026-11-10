package com.taskflow.orders.report.mapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import com.taskflow.orders.report.dto.SalesProtocol.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SalesMapper {
    @Select("SELECT sku_id,shop_id,name,unit_price_cents FROM sales_product WHERE sku_id=#{skuId}") Product product(long skuId);
    @Insert("INSERT INTO order_sale_snapshot(order_id,shop_id,sku_id,product_name,unit_price_cents,total_amount_cents) VALUES(#{orderId},#{product.shopId},#{product.skuId},#{product.name},#{product.unitPriceCents},#{amount})")
    void snapshot(String orderId, Product product, long amount);
    @Select("SELECT id FROM sales_shop WHERE id=#{shopId} FOR UPDATE") Long lockShop(long shopId);
    @Select("SELECT id FROM sales_shop WHERE id=#{shopId}") Long shop(long shopId);
    @Insert("""
        INSERT INTO sales_report_request(request_key,shop_id,requested_date,business_date,time_zone,revision_reason)
        VALUES(#{requestKey},#{shopId},#{requestedDate},#{businessDate},#{timeZone},#{revisionReason})
        """)
    void insertRequest(Request request);
    @Select("SELECT * FROM sales_report_request WHERE request_key=#{key}") Request request(String key);
    @Update("UPDATE sales_report_request SET report_id=#{id} WHERE request_key=#{key}")
    void completeRequest(String key, String id);
    @Select("""
        SELECT * FROM sales_report_revision WHERE shop_id=#{shopId} AND business_date=#{date} AND time_zone=#{zone}
        ORDER BY revision DESC LIMIT 1
        """)
    Report byScope(long shopId, LocalDate date, String zone);
    @Select("SELECT * FROM sales_report_revision WHERE id=#{id}") Report report(String id);
    @Select("""
        SELECT sku_id,MAX(CASE WHEN name_rank=1 THEN product_name END) product_name,
               COUNT(*) order_count,SUM(quantity) total_quantity,SUM(total_amount_cents) total_amount_cents
        FROM (
            SELECT s.sku_id,s.product_name,o.quantity,s.total_amount_cents,
                   ROW_NUMBER() OVER (PARTITION BY s.sku_id ORDER BY o.paid_at DESC,o.id DESC) name_rank
            FROM purchase_order o JOIN order_sale_snapshot s ON s.order_id=o.id
            WHERE s.shop_id=#{shopId} AND o.state='PAID' AND o.paid_at>=#{start} AND o.paid_at<#{end}
        ) sales
        GROUP BY sku_id ORDER BY sku_id
        """)
    List<Item> aggregate(long shopId, Instant start, Instant end);
    @Insert("""
        INSERT INTO sales_report_revision(id,shop_id,business_date,time_zone,order_count,total_quantity,total_amount_cents,
            generated_at,revision,revision_reason)
        VALUES(#{id},#{shopId},#{businessDate},#{timeZone},#{orderCount},#{totalQuantity},#{totalAmountCents},
            #{generatedAt},#{revision},#{revisionReason})
        """)
    void insertReport(Report report);
    @Insert("INSERT INTO sales_report_item(report_id,sku_id,product_name,order_count,total_quantity,total_amount_cents) VALUES(#{reportId},#{item.skuId},#{item.productName},#{item.orderCount},#{item.totalQuantity},#{item.totalAmountCents})")
    void insertItem(String reportId, Item item);
    @Select("SELECT sku_id,product_name,order_count,total_quantity,total_amount_cents FROM sales_report_item WHERE report_id=#{id} ORDER BY sku_id")
    List<Item> items(String id);
    String LATEST_SCOPE = """
        FROM sales_report_revision r
        WHERE NOT EXISTS (
            SELECT 1 FROM sales_report_revision newer WHERE newer.shop_id=r.shop_id
              AND newer.business_date=r.business_date AND newer.time_zone=r.time_zone AND newer.revision &gt; r.revision
        )
        <if test='shopId != null'>AND r.shop_id=#{shopId}</if>
        """;
    @Select("<script>SELECT r.* " + LATEST_SCOPE + " ORDER BY r.business_date DESC,r.shop_id,r.id LIMIT #{size} OFFSET #{offset}</script>")
    List<Report> list(Long shopId, int offset, int size);
    @Select("<script>SELECT COUNT(*) " + LATEST_SCOPE + "</script>")
    long count(Long shopId);
    @Select("""
        SELECT * FROM sales_report_revision
        WHERE shop_id=#{scope.shopId} AND business_date=#{scope.businessDate} AND time_zone=#{scope.timeZone}
        ORDER BY revision DESC LIMIT #{size} OFFSET #{offset}
        """)
    List<Report> revisions(Report scope, int offset, int size);
    @Select("""
        SELECT COUNT(*) FROM sales_report_revision
        WHERE shop_id=#{shopId} AND business_date=#{businessDate} AND time_zone=#{timeZone}
        """)
    long countRevisions(Report scope);
}

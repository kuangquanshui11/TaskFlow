package com.taskflow.orders.sample.mapper;
import java.time.*;
import com.taskflow.orders.report.dto.SalesProtocol.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SalesSampleMapper {
    @Select("SELECT dataset,start_date,days,order_count,seed FROM sales_sample_batch WHERE dataset=#{dataset}") Sample find(String dataset);
    @Insert("INSERT INTO sales_sample_batch(dataset,start_date,days,order_count,seed) VALUES(#{dataset},#{startDate},#{days},#{orderCount},#{seed})") void insert(Sample sample);
    @Insert("INSERT INTO sales_shop(id,name) VALUES(#{id},#{name})") void shop(long id,String name);
    @Insert("INSERT INTO sales_product(sku_id,shop_id,name,unit_price_cents) VALUES(#{skuId},#{shopId},#{name},#{unitPriceCents})") void product(Product product);
    @Update("UPDATE purchase_order SET created_at=#{created},expires_at=#{expires},paid_at=#{paid},closed_at=#{closed} WHERE id=#{id}")
    void dates(String id,Instant created,Instant expires,Instant paid,Instant closed);
    @Update("UPDATE stock_ledger SET created_at=CASE operation WHEN 'HELD' THEN #{created} WHEN 'CONSUMED' THEN #{paid} ELSE #{closed} END WHERE order_id=#{id}")
    void ledgerDates(String id,Instant created,Instant paid,Instant closed);
}

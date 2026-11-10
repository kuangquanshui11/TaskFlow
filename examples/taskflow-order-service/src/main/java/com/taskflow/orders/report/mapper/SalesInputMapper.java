package com.taskflow.orders.report.mapper;

import com.taskflow.orders.report.dto.SalesInput.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SalesInputMapper {
    @Select("SELECT id shop_id,name FROM sales_shop WHERE id=#{id}")
    Shop shop(long id);

    @Insert("INSERT INTO sales_shop(id,name) VALUES(#{shopId},#{name})")
    void insertShop(Shop shop);

    @Select("""
        SELECT sku_id,shop_id,name,unit_price_cents,catalog_source,catalog_version
        FROM sales_product WHERE sku_id=#{id}
        """)
    CatalogProduct product(long id);

    @Insert("""
        INSERT INTO sales_product(sku_id,shop_id,name,unit_price_cents,catalog_source,catalog_version)
        VALUES(#{skuId},#{shopId},#{name},#{unitPriceCents},#{catalogSource},#{catalogVersion})
        """)
    void insertProduct(CatalogProduct product);

    @Update("""
        UPDATE sales_product SET name=#{name},unit_price_cents=#{unitPriceCents},catalog_version=#{catalogVersion}
        WHERE sku_id=#{skuId}
        """)
    void updateProduct(CatalogProduct product);

    @Select("""
        SELECT order_id,shop_id,product_name,unit_price_cents,source_system,source_reference
        FROM order_sale_snapshot WHERE order_id=#{id}
        """)
    Backfill snapshot(String id);

    @Insert("""
        INSERT INTO order_sale_snapshot(order_id,shop_id,sku_id,product_name,unit_price_cents,total_amount_cents,
            source_system,source_reference)
        VALUES(#{input.orderId},#{input.shopId},#{skuId},#{input.productName},#{input.unitPriceCents},#{total},
            #{input.sourceSystem},#{input.sourceReference})
        """)
    void insertSnapshot(Backfill input, long skuId, long total);
}

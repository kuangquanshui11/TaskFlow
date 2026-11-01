package com.taskflow.orders.order.mapper;

import java.time.Instant;
import java.util.List;
import com.taskflow.orders.order.dto.OrderProtocol.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OrderMapper {
    @Select("SELECT * FROM purchase_order WHERE id=#{id} FOR UPDATE") Order lock(String id);
    @Select("SELECT * FROM purchase_order WHERE id=#{id}") Order find(String id);
    @Select("SELECT * FROM purchase_order WHERE request_key=#{key}") Order byKey(String key);
    @Insert("INSERT INTO purchase_order(id,request_key,sku_id,quantity,state,created_at,expires_at) VALUES(#{id},#{requestKey},#{skuId},#{quantity},#{state},#{createdAt},#{expiresAt})")
    void insert(Order order);
    @Update("UPDATE purchase_order SET state='PAID',paid_at=#{now} WHERE id=#{id} AND state='PENDING'") int pay(String id, Instant now);
    @Update("UPDATE purchase_order SET state='CLOSED',closed_at=#{now},close_reason='PAYMENT_TIMEOUT' WHERE id=#{id} AND state='PENDING'") int close(String id, Instant now);
    // 游标跨过失败候选，避免单个坏订单使后面的订单永久饥饿；下一次 Run 从头重扫。
    @Select("""
        <script>SELECT * FROM purchase_order WHERE state='PENDING' AND expires_at &lt;= #{cutoff}
        <if test='afterId != null'>AND (expires_at &gt; #{afterExpiresAt} OR (expires_at=#{afterExpiresAt} AND id &gt; #{afterId}))</if>
        ORDER BY expires_at,id LIMIT #{batchSize}</script>
        """)
    List<Order> candidates(Batch batch);
    @Select("SELECT order_id,sku_id,operation,quantity,request_id,created_at FROM stock_ledger WHERE order_id=#{id} ORDER BY created_at,id")
    List<Ledger> ledger(String id);
}

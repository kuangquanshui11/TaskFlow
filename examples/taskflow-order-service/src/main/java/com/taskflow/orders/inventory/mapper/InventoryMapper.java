package com.taskflow.orders.inventory.mapper;

import java.time.Instant;
import java.util.List;
import com.taskflow.orders.inventory.model.Stock;
import org.apache.ibatis.annotations.*;

@Mapper
public interface InventoryMapper {
    @Insert("INSERT INTO sku_stock(sku_id,available,reserved,version) VALUES(#{skuId},#{available},0,0)") void insert(long skuId, long available);
    @Select("SELECT * FROM sku_stock WHERE sku_id=#{skuId}") Stock find(long skuId);
    @Update("UPDATE sku_stock SET available=available-#{quantity},reserved=reserved+#{quantity},version=version+1 WHERE sku_id=#{skuId} AND available>=#{quantity}")
    int hold(long skuId, int quantity);
    @Insert("INSERT INTO stock_reservation(order_id,sku_id,quantity,state) VALUES(#{id},#{skuId},#{quantity},'HELD')") void reserve(String id, long skuId, int quantity);
    @Update("UPDATE stock_reservation SET state=#{state} WHERE order_id=#{id} AND state='HELD'") int transition(String id, String state);
    @Update("UPDATE sku_stock SET available=available+#{returned},reserved=reserved-#{quantity},version=version+1 WHERE sku_id=#{skuId} AND reserved>=#{quantity}")
    int settle(long skuId, int quantity, int returned);
    @Insert("INSERT INTO stock_ledger(id,order_id,sku_id,operation,quantity,request_id,created_at) VALUES(#{id},#{orderId},#{skuId},#{operation},#{quantity},#{requestId},#{now})")
    void ledger(String id, String orderId, long skuId, String operation, int quantity, String requestId, Instant now);
    @Insert("INSERT INTO stock_outbox(id,sku_id,created_at,next_attempt_at) VALUES(#{id},#{skuId},#{now},#{now})") void enqueue(String id, long skuId, Instant now);
    record Pending(String id, long skuId, int attempts) { }
    @Select("SELECT id,sku_id,attempts FROM stock_outbox WHERE next_attempt_at<=#{now} AND attempts<#{max} ORDER BY next_attempt_at,created_at LIMIT 100")
    List<Pending> pending(Instant now, int max);
    @Delete("DELETE FROM stock_outbox WHERE id=#{id}") void published(String id);
    @Update("UPDATE stock_outbox SET attempts=attempts+1,next_attempt_at=#{next} WHERE id=#{id} AND attempts=#{attempts}") void defer(String id, int attempts, Instant next);
}

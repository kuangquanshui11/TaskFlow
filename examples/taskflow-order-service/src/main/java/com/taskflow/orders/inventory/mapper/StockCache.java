package com.taskflow.orders.inventory.mapper;

import java.time.Duration;
import java.util.List;
import com.taskflow.orders.inventory.model.Stock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class StockCache {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> SNAPSHOT = new DefaultRedisScript<>("""
        local old = redis.call('HGET', KEYS[1], 'version')
        if old and tonumber(old) > tonumber(ARGV[1]) then return 0 end
        redis.call('HSET', KEYS[1], 'version', ARGV[1], 'available', ARGV[2], 'reserved', ARGV[3])
        redis.call('PEXPIRE', KEYS[1], ARGV[4])
        return 1
        """, Long.class);
    public StockCache(StringRedisTemplate redis) { this.redis = redis; }
    private String key(long id) { return "taskflow:order:stock:v1:" + id; }
    public Stock get(long id) {
        var values = redis.opsForHash().entries(key(id));
        if (values.isEmpty()) return null;
        return new Stock(id, Long.parseLong(values.get("available").toString()),
                Long.parseLong(values.get("reserved").toString()), Long.parseLong(values.get("version").toString()));
    }
    public void put(Stock stock, Duration ttl) {
        redis.execute(SNAPSHOT, List.of(key(stock.skuId())), Long.toString(stock.version()),
                Long.toString(stock.available()), Long.toString(stock.reserved()), Long.toString(ttl.toMillis()));
    }
    public void invalidate(long id) { redis.delete(key(id)); }
}

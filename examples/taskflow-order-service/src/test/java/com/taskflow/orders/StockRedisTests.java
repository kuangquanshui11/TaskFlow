package com.taskflow.orders;

import java.time.Duration;
import com.taskflow.orders.inventory.mapper.StockCache;
import com.taskflow.orders.inventory.model.Stock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles({"test","test-redis"})
@EnabledIfEnvironmentVariable(named="TASKFLOW_ORDER_TEST_REDIS_PORT",matches="[0-9]+")
class StockRedisTests {
    @Autowired StockCache cache;
    @Test void luaRejectsOlderSnapshotAndKeysExpire() {
        cache.put(new Stock(999999,7,3,2),Duration.ofSeconds(2));
        cache.put(new Stock(999999,10,0,1),Duration.ofSeconds(2));
        assertThat(cache.get(999999)).isEqualTo(new Stock(999999,7,3,2));
        cache.put(new Stock(999999,8,2,3),Duration.ofMillis(250));
        assertThat(cache.get(999999).version()).isEqualTo(3);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(cache.get(999999)).isNull());
    }
}

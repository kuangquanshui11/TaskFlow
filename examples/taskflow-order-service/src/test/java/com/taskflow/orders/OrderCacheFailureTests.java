package com.taskflow.orders;

import java.util.UUID;
import com.taskflow.orders.inventory.mapper.StockCache;
import com.taskflow.orders.inventory.service.IInventoryService;
import com.taskflow.orders.order.dto.OrderProtocol.Create;
import com.taskflow.orders.order.service.IOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="order.cache-enabled=true")
@ActiveProfiles("test")
class OrderCacheFailureTests {
    @Autowired IInventoryService inventory;
    @Autowired IOrderService orders;
    @MockitoBean StockCache cache;
    @Test void redisOutageAndStalePositiveCacheCannotAuthorizeStock() {
        doThrow(new IllegalStateException("Redis down")).when(cache).invalidate(anyLong());
        doThrow(new IllegalStateException("Redis down")).when(cache).put(any(),any());
        when(cache.get(anyLong())).thenThrow(new IllegalStateException("Redis down"));
        inventory.insert(900001,1);
        orders.insert(new Create(UUID.randomUUID().toString(),900001,1));
        assertThat(inventory.select(900001).available()).isZero();
        doReturn(new com.taskflow.orders.inventory.model.Stock(900001,100,0,0)).when(cache).get(900001);
        assertThat(inventory.select(900001).available()).isEqualTo(100);
        assertThatThrownBy(()->orders.insert(new Create(UUID.randomUUID().toString(),900001,1)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}

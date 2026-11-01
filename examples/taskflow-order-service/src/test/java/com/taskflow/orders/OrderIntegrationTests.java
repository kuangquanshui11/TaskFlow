package com.taskflow.orders;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.taskflow.orders.inventory.mapper.InventoryMapper;
import com.taskflow.orders.inventory.service.IInventoryService;
import com.taskflow.orders.order.dto.OrderProtocol.*;
import com.taskflow.orders.order.service.IOrderService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class OrderIntegrationTests {
    @Autowired IOrderService orders;
    @Autowired IInventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean InventoryMapper stocks;
    private static final AtomicLong SKUS=new AtomicLong(1000);
    long sku(long amount) { long id=SKUS.incrementAndGet(); inventory.insert(id,amount); return id; }
    Create request(long sku,int count) { return new Create(UUID.randomUUID().toString(),sku,count); }
    void expire(String id) { jdbc.update("UPDATE purchase_order SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(60)),id); }
    BatchResult scan(int size) { return orders.updateTimeout(new Batch(size,null,null,null),"test-run"); }
    <T> List<T> concurrent(int count,Callable<T> action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(24)) {
            var start=new CountDownLatch(1); var futures=new ArrayList<Future<T>>();
            for(int i=0;i<count;i++) futures.add(pool.submit(()->{ start.await(); return action.call(); }));
            start.countDown(); var result=new ArrayList<T>();
            for(var future:futures) result.add(future.get(30,TimeUnit.SECONDS));
            return result;
        }
    }
    @Test void hundredBuyersCannotOversellTenUnits() throws Exception {
        long sku=sku(10);
        var results=concurrent(100,()-> { try { orders.insert(request(sku,1));return true; }
            catch(org.springframework.web.server.ResponseStatusException insufficient) { return false; } });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(10);
        assertThat(inventory.select(sku).available()).isZero();
        assertThat(inventory.select(sku).reserved()).isEqualTo(10);
    }
    @Test void concurrentRequestKeyReservesOnlyOnceAndConflictingPayloadFails() throws Exception {
        long sku=sku(10);var input=request(sku,2);
        var results=concurrent(24,()->orders.insert(input).id());
        assertThat(new HashSet<>(results)).hasSize(1);
        assertThat(inventory.select(sku).available()).isEqualTo(8);
        assertThatThrownBy(()->orders.insert(new Create(input.requestKey(),sku,3)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void expiryPaymentAndRepeatedScansHaveLegalStates() {
        long sku=sku(10);
        var pending=orders.insert(request(sku,1));var paid=orders.insert(request(sku,2));
        orders.updatePayment(paid.id());var expired=orders.insert(request(sku,3));expire(expired.id());
        scan(500);scan(500);
        assertThat(orders.select(pending.id()).order().state()).isEqualTo("PENDING");
        assertThat(orders.select(paid.id()).order().state()).isEqualTo("PAID");
        assertThat(orders.select(expired.id()).order().state()).isEqualTo("CLOSED");
        assertThat(orders.select(expired.id()).ledger()).extracting(Ledger::operation).containsExactly("HELD","RELEASED");
        assertThat(inventory.select(sku).available()).isEqualTo(7);
        assertThat(inventory.select(sku).reserved()).isEqualTo(1);
        assertThatThrownBy(()->orders.updatePayment(expired.id())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void concurrentPaymentAndCloseNeverBothConsumeAndRelease() throws Exception {
        long sku=sku(40);
        for(int i=0;i<20;i++) {
            var order=orders.insert(request(sku,1));
            // 严格截止规则：到期付款应拒绝；同时让扫描线程和付款线程竞争同一订单锁。
            expire(order.id());
            concurrent(2,()-> { if(Thread.currentThread().getName().endsWith("1")) {
                try { orders.updatePayment(order.id()); } catch(org.springframework.web.server.ResponseStatusException conflict) { }
            } else scan(500); return true; });
            assertThat(orders.select(order.id()).order().state()).isEqualTo("CLOSED");
            assertThat(orders.select(order.id()).ledger()).extracting(Ledger::operation).containsExactly("HELD","RELEASED");
        }
        assertThat(inventory.select(sku).available()).isEqualTo(40);
        assertThat(inventory.select(sku).reserved()).isZero();
    }
    @Test void stockFailureRollsBackOrderReservationLedgerAndOutbox() {
        long sku=sku(10);var order=orders.insert(request(sku,2));expire(order.id());
        doReturn(0).when(stocks).settle(sku,2,2);
        try { scan(500); } finally { reset(stocks); }
        assertThat(orders.select(order.id()).order().state()).isEqualTo("PENDING");
        assertThat(orders.select(order.id()).ledger()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT state FROM stock_reservation WHERE order_id=?",String.class,order.id())).isEqualTo("HELD");
        assertThat(inventory.select(sku).available()).isEqualTo(8);
        scan(500);
        assertThat(inventory.select(sku).available()).isEqualTo(10);
    }
    @Test void failedCreationLeavesNoOrderOrInventoryChange() {
        long sku=sku(3); var input=request(sku,2);
        doThrow(new IllegalStateException("injected failure")).when(stocks).reserve(anyString(),eq(sku),eq(2));
        try { assertThatThrownBy(()->orders.insert(input)).isInstanceOf(IllegalStateException.class); } finally { reset(stocks); }
        assertThat(inventory.select(sku).available()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE request_key=?",Integer.class,input.requestKey())).isZero();
    }
    @Test void lostBatchResponseAndRestartHandleRemainder() {
        long sku=sku(20); var ids=new ArrayList<String>();
        for(int i=0;i<9;i++){var order=orders.insert(request(sku,1));expire(order.id());ids.add(order.id());}
        // 一个批次提交后丢弃响应，新的执行从头开始，已释放记录不会再次释放。
        scan(2);
        Batch next=new Batch(2,null,null,null);int closed=0;
        for(int i=0;i<10;i++) {
            var result=orders.updateTimeout(next,"new-run");closed+=result.closed();
            if(result.scanned()<2) break;
            next=new Batch(2,result.cutoff(),result.afterExpiresAt(),result.afterId());
        }
        assertThat(closed).isEqualTo(7);assertThat(inventory.select(sku).available()).isEqualTo(20);
        for(var id:ids) assertThat(orders.select(id).ledger()).hasSize(2);
    }
    @Test void failedCandidateDoesNotStarveFollowingPages() {
        long sku=sku(5);var ids=new ArrayList<String>();
        for(int i=0;i<5;i++){var order=orders.insert(request(sku,1));expire(order.id());ids.add(order.id());}
        doThrow(new IllegalStateException("one poisoned order")).when(stocks).transition(ids.getFirst(),"RELEASED");
        int closed=0,failed=0;Batch next=new Batch(2,null,null,null);
        try {
            for(int i=0;i<10;i++) {
                var result=orders.updateTimeout(next,"cursor-run");closed+=result.closed();failed+=result.failed();
                if(result.scanned()<2)break;
                next=new Batch(2,result.cutoff(),result.afterExpiresAt(),result.afterId());
            }
        } finally {reset(stocks);}
        assertThat(closed).isEqualTo(4);assertThat(failed).isEqualTo(1);
        scan(500);assertThat(inventory.select(sku).available()).isEqualTo(5);
    }
}

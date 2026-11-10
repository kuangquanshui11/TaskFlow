package com.taskflow.orders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.taskflow.orders.inventory.service.IInventoryService;
import com.taskflow.orders.order.dto.OrderProtocol.Batch;
import com.taskflow.orders.order.dto.OrderProtocol.Create;
import com.taskflow.orders.order.dto.OrderProtocol.Ledger;
import com.taskflow.orders.order.dto.OrderProtocol.Order;
import com.taskflow.orders.order.service.IOrderService;
import com.taskflow.orders.payment.dto.PaymentProtocol.Release;
import com.taskflow.orders.payment.dto.PaymentProtocol.Status;
import com.taskflow.orders.payment.model.PaymentWork.Kind;
import com.taskflow.orders.payment.service.IInventoryReleaseClient;
import com.taskflow.orders.payment.service.IOrderPaymentService;
import com.taskflow.orders.payment.service.IPaymentWorkService;
import com.taskflow.orders.payment.service.IWechatPayClient;
import com.taskflow.orders.payment.service.IWechatPayClient.Refund;
import com.taskflow.orders.payment.service.IWechatPayClient.RemoteFailure;
import com.taskflow.orders.payment.service.IWechatPayClient.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "test-payment"})
class OrderPaymentTests {
    private static final AtomicLong SKUS = new AtomicLong(600_000);
    private static final String CODE_URL = "weixin://wxpay/bizpayurl?pr=test";

    @Autowired IOrderService orders;
    @Autowired IOrderPaymentService payments;
    @Autowired IInventoryService inventory;
    @Autowired IPaymentWorkService work;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @MockitoBean IWechatPayClient wechat;
    @MockitoBean IInventoryReleaseClient releases;

    @BeforeEach
    void resetWork() {
        jdbc.update("DELETE FROM order_payment_outbox");
        jdbc.update("DELETE FROM order_payment");
    }

    @Test
    void nativeIntentUsesSnapshotAndTrustedCallbackConsumesOnce() throws Exception {
        var order = pricedOrder();
        jdbc.update("UPDATE sales_product SET unit_price_cents=999 WHERE sku_id=?", order.skuId());
        var path = "/api/v1/order-payments/insertNative";
        var body = "{\"orderId\":\"" + order.id() + "\"}";
        assertThat(post(path, "test-order-worker", body, false)).isEqualTo(401);
        assertThat(post(path, "test-order-api", body, false)).isEqualTo(202);
        assertThat(post(path, "test-order-api", body, false)).isEqualTo(202);
        assertThat(post("/api/v1/orders/updatePayment", "test-order-api", body, false)).isEqualTo(409);
        var payment = payments.select(order.id());
        assertThat(payment.amountCents()).isEqualTo(250);
        assertThat(payment.codeUrl()).isNull();
        when(wechat.query(any())).thenThrow(new RemoteFailure("WECHAT_ORDER_NOT_EXIST", false));
        when(wechat.prepay(any(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(call.<com.taskflow.orders.payment.model.PaymentOrder>getArgument(0).amountCents()).isEqualTo(250);
            return CODE_URL;
        });
        work.scan();
        assertThat(payments.select(order.id()).codeUrl()).isEqualTo(CODE_URL);
        verify(wechat, times(1)).prepay(any(), any());

        var callback = "/api/v1/order-payments/updateWechatNotification";
        var paidAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var trade = trade(payment, paidAt, 250);
        when(wechat.decode(any())).thenReturn(trade(payment, paidAt, 249));
        assertThat(post(callback, null, "{}", true)).isEqualTo(400);
        assertThat(orders.select(order.id()).order().state()).isEqualTo("PENDING");
        when(wechat.decode(any())).thenReturn(trade);

        assertThat(concurrent(List.of(() -> post(callback, null, "{}", true),
                () -> post(callback, null, "{}", true), () -> post(callback, null, "{}", true))))
                .containsOnly(204).hasSize(3);
        assertThat(orders.select(order.id()).ledger()).extracting(Ledger::operation).containsExactly("HELD", "CONSUMED");
        assertThat(payments.select(order.id()).paymentState()).isEqualTo("PAID");
        assertThat(payments.select(order.id()).codeUrl()).isNull();
        assertThat(inventory.select(order.skuId()).available()).isEqualTo(8);
        assertThat(inventory.select(order.skuId()).reserved()).isZero();
        verifyNoInteractions(releases);
    }

    @Test
    void missingPriceCannotCreatePaymentAndLostCallbackIsRecoveredByQuery() {
        long sku = SKUS.incrementAndGet();
        inventory.insert(sku, 10);
        var unpriced = orders.insert(new Create(UUID.randomUUID().toString(), sku, 1));
        assertThatThrownBy(() -> payments.insert(unpriced.id())).hasMessageContaining("ORDER_PAYMENT_PRICE_UNAVAILABLE");
        assertThat(payments.select(unpriced.id()).paymentState()).isEqualTo("NOT_STARTED");

        var order = pricedOrder();
        var payment = payments.insert(order.id());
        when(wechat.query(any())).thenReturn(trade(payment, Instant.now().truncatedTo(ChronoUnit.SECONDS), 250));
        work.scan();
        assertThat(orders.select(order.id()).order().state()).isEqualTo("PAID");
        assertThat(payments.select(order.id()).payment().state()).isEqualTo("DONE");
        verify(wechat, never()).prepay(any(), any());
    }

    @Test
    void alreadyClosedOrderRefundsEvenWhenProviderPaidBeforeDeadline() {
        var order = pricedOrder();
        var payment = payments.insert(order.id());
        var expiresAt = expire(order);
        closeOrders();
        // Equal timestamps must show the reservation first even when UUID order says otherwise.
        var sameTime = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        var idSuffix = order.id().substring(1);
        jdbc.update("UPDATE stock_ledger SET created_at=?,id=? WHERE order_id=? AND operation='HELD'",
                sameTime, "f" + idSuffix, order.id());
        jdbc.update("UPDATE stock_ledger SET created_at=?,id=? WHERE order_id=? AND operation='RELEASED'",
                sameTime, "0" + idSuffix, order.id());
        payments.recordTrade(trade(payment, expiresAt.minusSeconds(1), 250));
        assertThat(orders.select(order.id()).order().state()).isEqualTo("CLOSED");
        assertThat(payments.select(order.id()).refundState()).isEqualTo("PENDING");
        assertThat(orders.select(order.id()).ledger()).extracting(Ledger::operation).containsExactly("HELD", "RELEASED");
    }

    @Test
    void deadlineRaceAndLostResponsesReuseRefundNumberAndStockReleaseKey() throws Exception {
        var order = pricedOrder();
        var payment = payments.insert(order.id());
        var deadline = expire(order);
        var lateTrade = trade(payment, deadline, 250);
        concurrent(List.of(() -> { closeOrders(); return true; },
                () -> { payments.recordTrade(lateTrade); return true; },
                () -> { payments.recordTrade(lateTrade); return true; }));
        var late = payments.select(order.id());
        assertThat(late.paymentState()).isEqualTo("LATE_PAID");
        assertThat(late.refundNo()).isEqualTo("late-" + payment.tradeNo());
        assertThat(orders.select(order.id()).ledger()).extracting(Ledger::operation).containsExactly("HELD", "RELEASED");
        assertThat(inventory.select(order.skuId()).available()).isEqualTo(10);

        when(wechat.queryRefund(any())).thenThrow(new RemoteFailure("WECHAT_RESOURCE_NOT_EXISTS", false))
                .thenReturn(refund(late, "PROCESSING"), refund(late, "SUCCESS"));
        doThrow(new RemoteFailure("WECHAT_UNCONFIRMED", true)).when(wechat).refund(any());
        doThrow(new RemoteFailure("INVENTORY_UNCONFIRMED", true)).doNothing().when(releases).release(any());
        work.scan();
        assertThat(payments.select(order.id()).refundState()).isEqualTo("PENDING");
        assertThat(payments.select(order.id()).inventoryRelease().state()).isEqualTo("READY");

        due(order.id());
        work.scan();
        assertThat(payments.select(order.id()).refundState()).isEqualTo("PROCESSING");
        assertThat(payments.select(order.id()).inventoryRelease().state()).isEqualTo("DONE");
        due(order.id());
        work.scan();
        assertThat(payments.select(order.id()).refundState()).isEqualTo("SUCCESS");
        assertThat(payments.select(order.id()).refund().state()).isEqualTo("DONE");
        verify(wechat, times(1)).refund(argThat(p -> p.refundNo().equals(late.refundNo())));
        var events = ArgumentCaptor.forClass(Release.class);
        verify(releases, times(2)).release(events.capture());
        assertThat(events.getAllValues()).containsOnly(new Release("order-release-" + order.id(), order.id(),
                order.skuId(), 2, "PAYMENT_TIMEOUT"));
    }

    @Test
    void unpaidWechatTradeIsClosedAndRejectedCompensationCanBeRequeued() {
        var order = pricedOrder();
        var payment = payments.insert(order.id());
        expire(order);
        closeOrders();
        when(wechat.query(any())).thenReturn(new Trade("wxTest", "123456", payment.tradeNo(),
                null, "NATIVE", "NOTPAY", null, 250, "CNY"));
        doThrow(new RemoteFailure("INVENTORY_HTTP_403", false)).doNothing().when(releases).release(any());
        work.scan();
        verify(wechat).close(argThat(p -> p.tradeNo().equals(payment.tradeNo())));
        assertThat(payments.select(order.id()).paymentState()).isEqualTo("CLOSED");
        assertThat(payments.select(order.id()).inventoryRelease().state()).isEqualTo("REVIEW");
        payments.retry(order.id(), Kind.RELEASE);
        work.scan();
        assertThat(payments.select(order.id()).inventoryRelease().state()).isEqualTo("DONE");
    }

    @Test
    void acceptedRefundWaitsForQueryAndAbnormalRefundCannotBeAutomaticallyReissued() {
        var order = pricedOrder();
        var payment = payments.insert(order.id());
        var deadline = expire(order);
        payments.recordTrade(trade(payment, deadline, 250));
        var late = payments.select(order.id());
        when(wechat.queryRefund(any())).thenThrow(new RemoteFailure("WECHAT_RESOURCE_NOT_EXISTS", false))
                .thenReturn(refund(late, "ABNORMAL"));
        work.scan();
        assertThat(payments.select(order.id()).refundState()).isEqualTo("PROCESSING");
        assertThat(payments.select(order.id()).refund().state()).isEqualTo("READY");
        due(order.id());
        work.scan();
        assertThat(payments.select(order.id()).refundState()).isEqualTo("ABNORMAL");
        assertThat(payments.select(order.id()).refund().state()).isEqualTo("REVIEW");
        assertThatThrownBy(() -> payments.retry(order.id(), Kind.REFUND)).hasMessageContaining("PAYMENT_WORK_NOT_RETRYABLE");
        verify(wechat, times(1)).refund(any());
    }

    private Order pricedOrder() {
        long sku = SKUS.incrementAndGet();
        inventory.insert(sku, 10);
        jdbc.update("INSERT INTO sales_product(sku_id,shop_id,name,unit_price_cents) VALUES (?,1,'Test SKU',125)", sku);
        return orders.insert(new Create(UUID.randomUUID().toString(), sku, 2));
    }

    private Instant expire(Order order) {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var deadline = now.minusSeconds(10);
        jdbc.update("UPDATE purchase_order SET created_at=?, expires_at=? WHERE id=?",
                Timestamp.from(now.minusSeconds(120)), Timestamp.from(deadline), order.id());
        return deadline;
    }

    private void closeOrders() {
        orders.updateTimeout(new Batch(500, null, null, null), "payment-test");
    }

    private void due(String orderId) {
        jdbc.update("UPDATE order_payment_outbox SET next_at=? WHERE order_id=? AND state='READY'",
                Timestamp.from(Instant.now().minusSeconds(1)), orderId);
    }

    private static Trade trade(Status payment, Instant paidAt, int amount) {
        return new Trade("wxTest", "123456", payment.tradeNo(), "123456789",
                "NATIVE", "SUCCESS", paidAt, amount, "CNY");
    }

    private static Refund refund(Status payment, String state) {
        return new Refund(payment.refundNo(), payment.tradeNo(), payment.transactionId(), "987654321",
                state, 250L, 250L, "CNY");
    }

    private int post(String path, String token, String body, boolean webhook) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("X-Taskflow-Token", token);
        if (webhook) request.header("Wechatpay-Serial", "test").header("Wechatpay-Timestamp", "test")
                .header("Wechatpay-Nonce", "test").header("Wechatpay-Signature", "test");
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private static <T> List<T> concurrent(List<Callable<T>> calls) throws Exception {
        try (var executor = Executors.newFixedThreadPool(calls.size())) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<T>>();
            for (var call : calls) futures.add(executor.submit(() -> { start.await(); return call.call(); }));
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }
}

package com.taskflow.orders.payment.service.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import com.taskflow.orders.inventory.service.IInventoryService;
import com.taskflow.orders.order.dto.OrderProtocol.Order;
import com.taskflow.orders.order.mapper.OrderMapper;
import com.taskflow.orders.payment.config.PaymentWorkProperties;
import com.taskflow.orders.payment.config.WechatPaymentProperties;
import com.taskflow.orders.payment.dto.PaymentProtocol.*;
import com.taskflow.orders.payment.mapper.PaymentMapper;
import com.taskflow.orders.payment.model.PaymentOrder;
import com.taskflow.orders.payment.model.PaymentWork;
import com.taskflow.orders.payment.model.PaymentWork.Kind;
import com.taskflow.orders.payment.service.IOrderPaymentService;
import com.taskflow.orders.payment.service.IWechatPayClient;
import com.taskflow.orders.payment.service.IWechatPayClient.Trade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderPaymentServiceImpl implements IOrderPaymentService {
    private final OrderMapper orders;
    private final PaymentMapper payments;
    private final IInventoryService inventory;
    private final WechatPaymentProperties wechat;
    private final PaymentWorkProperties work;
    private final ObjectProvider<IWechatPayClient> client;

    public OrderPaymentServiceImpl(OrderMapper orders, PaymentMapper payments, IInventoryService inventory,
                                   WechatPaymentProperties wechat, PaymentWorkProperties work,
                                   ObjectProvider<IWechatPayClient> client) {
        this.orders = orders;
        this.payments = payments;
        this.inventory = inventory;
        this.wechat = wechat;
        this.work = work;
        this.client = client;
    }

    @Override
    @Transactional
    public Status insert(String orderId) {
        if (!wechat.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WECHAT_PAY_DISABLED");
        var order = require(orders.lock(orderId));
        var existing = payments.lock(orderId);
        if (existing != null) return status(order, existing);
        // TIMESTAMP(6) must not round an immediately eligible task into the future.
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!order.state().equals("PENDING") || !now.isBefore(order.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ORDER_CLOSED_OR_EXPIRED");
        }
        var price = payments.price(orderId);
        if (price == null || price.amountCents() < 1 || price.amountCents() > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ORDER_PAYMENT_PRICE_UNAVAILABLE");
        }
        // 一个订单只绑定一个商户单号及不可变金额，后续所有重试复用。
        var payment = new PaymentOrder(orderId, orderId.replace("-", ""), wechat.appId(), wechat.merchantId(),
                price.description(), (int) price.amountCents(), "PENDING", null, null, null, "NONE", null);
        payments.insert(payment);
        payments.enqueue(orderId, Kind.PAYMENT, now, order.expiresAt().plus(work.reconciliationWindow()));
        return status(order, payment);
    }

    @Override
    @Transactional(readOnly = true)
    public Status select(String orderId) {
        return status(require(orders.find(orderId)), payments.find(orderId));
    }

    @Override
    @Transactional
    public Status retry(String orderId, Kind kind) {
        var order = require(orders.lock(orderId));
        boolean enabled = kind == Kind.RELEASE ? work.inventoryReleaseEnabled() : wechat.enabled();
        if (!enabled) throw new ResponseStatusException(HttpStatus.CONFLICT, "PAYMENT_WORK_DISABLED");
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (payments.retry(orderId, kind, now, now.plus(work.reconciliationWindow())) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PAYMENT_WORK_NOT_RETRYABLE");
        }
        return status(order, payments.find(orderId));
    }

    @Override
    @Transactional
    public void notifyPayment(Notification notification) {
        if (!wechat.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WECHAT_PAY_DISABLED");
        recordTrade(client.getObject().decode(notification));
    }

    @Override
    @Transactional
    public void recordTrade(Trade trade) {
        if (!"SUCCESS".equals(trade.state()) || trade.tradeNo() == null
                || trade.transactionId() == null || !trade.transactionId().matches("[0-9]{1,32}")
                || trade.paidAt() == null || !"NATIVE".equals(trade.tradeType()) || !"CNY".equals(trade.currency())) {
            throw invalidTrade();
        }
        var orderId = payments.orderId(trade.tradeNo());
        if (orderId == null) throw invalidTrade();
        var order = require(orders.lock(orderId));
        var payment = payments.lock(order.id());
        if (!payment.appId().equals(trade.appId()) || !payment.merchantId().equals(trade.merchantId())
                || !Objects.equals(trade.amountCents(), payment.amountCents())
                || trade.paidAt().isBefore(order.createdAt().truncatedTo(ChronoUnit.SECONDS))
                || trade.paidAt().isAfter(Instant.now().plusSeconds(60))) {
            throw invalidTrade();
        }
        var paidAt = trade.paidAt().truncatedTo(ChronoUnit.MICROS);
        if (payment.transactionId() != null) {
            if (!payment.transactionId().equals(trade.transactionId()) || !payment.paidAt().equals(paidAt)) {
                throw invalidTrade();
            }
            return;
        }

        // 先锁订单再决定库存去向。已关闭的订单即使按时付款也不能重新占用库存。
        boolean accept = order.state().equals("PENDING") && trade.paidAt().isBefore(order.expiresAt());
        if (accept) {
            inventory.settle(order.id(), order.skuId(), order.quantity(), false, trade.transactionId());
            if (orders.pay(order.id(), paidAt) != 1) throw new IllegalStateException("Order changed under lock");
        } else {
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            if (order.state().equals("PENDING")) {
                inventory.settle(order.id(), order.skuId(), order.quantity(), true, trade.transactionId());
                if (orders.close(order.id(), now) != 1) throw new IllegalStateException("Order changed under lock");
                enqueueInventoryRelease(order);
            }
            payments.enqueue(order.id(), Kind.REFUND, now, now.plus(work.reconciliationWindow()));
        }
        payments.recordPayment(order.id(), accept ? "PAID" : "LATE_PAID", trade.transactionId(), paidAt,
                accept ? "NONE" : "PENDING");
        payments.completePayment(order.id());
    }

    @Override
    public void checkSimulationAllowed(String orderId) {
        if (wechat.enabled() || payments.lock(orderId) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SIMULATION_PAYMENT_DISABLED");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueInventoryRelease(Order order) {
        if (work.inventoryReleaseEnabled()) {
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            payments.enqueue(order.id(), Kind.RELEASE, now, now.plus(work.reconciliationWindow()));
        }
    }

    private Status status(Order order, PaymentOrder payment) {
        var id = order.id();
        var release = delivery(payments.work(id, Kind.RELEASE));
        if (payment == null) {
            return new Status(id, null, 0, order.expiresAt(), "NOT_STARTED", null, null, null,
                    null, "NONE", null, null, null, release);
        }
        var codeUrl = order.state().equals("PENDING") && Instant.now().isBefore(order.expiresAt())
                ? payment.codeUrl() : null;
        return new Status(id, payment.tradeNo(), payment.amountCents(), order.expiresAt(), payment.paymentState(),
                codeUrl, payment.transactionId(), payment.paidAt(), payment.refundState().equals("NONE") ? null : payment.refundNo(),
                payment.refundState(), payment.refundId(), delivery(payments.work(id, Kind.PAYMENT)),
                delivery(payments.work(id, Kind.REFUND)), release);
    }

    private static Delivery delivery(PaymentWork row) {
        return row == null ? null : new Delivery(row.state(), row.attempts(), row.lastError(), row.nextAt());
    }

    private static Order require(Order order) {
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        return order;
    }

    private static ResponseStatusException invalidTrade() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "WECHAT_TRADE_MISMATCH");
    }
}

package com.taskflow.orders.payment.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import com.taskflow.orders.order.mapper.OrderMapper;
import com.taskflow.orders.payment.config.PaymentWorkProperties;
import com.taskflow.orders.payment.config.WechatPaymentProperties;
import com.taskflow.orders.payment.dto.PaymentProtocol.Release;
import com.taskflow.orders.payment.mapper.PaymentMapper;
import com.taskflow.orders.payment.model.PaymentOrder;
import com.taskflow.orders.payment.model.PaymentWork;
import com.taskflow.orders.payment.model.PaymentWork.Kind;
import com.taskflow.orders.payment.service.IInventoryReleaseClient;
import com.taskflow.orders.payment.service.IOrderPaymentService;
import com.taskflow.orders.payment.service.IPaymentWorkService;
import com.taskflow.orders.payment.service.IWechatPayClient;
import com.taskflow.orders.payment.service.IWechatPayClient.RemoteFailure;
import com.taskflow.orders.payment.service.IWechatPayClient.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentWorkServiceImpl implements IPaymentWorkService {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentWorkServiceImpl.class);
    private final PaymentMapper payments;
    private final OrderMapper orders;
    private final IOrderPaymentService orderPayments;
    private final PaymentWorkProperties properties;
    private final WechatPaymentProperties wechat;
    private final ObjectProvider<IWechatPayClient> wechatClient;
    private final ObjectProvider<IInventoryReleaseClient> inventoryClient;
    private final TransactionTemplate transactions;

    public PaymentWorkServiceImpl(PaymentMapper payments, OrderMapper orders, IOrderPaymentService orderPayments,
                                  PaymentWorkProperties properties, WechatPaymentProperties wechat,
                                  ObjectProvider<IWechatPayClient> wechatClient,
                                  ObjectProvider<IInventoryReleaseClient> inventoryClient,
                                  PlatformTransactionManager manager) {
        this.payments = payments;
        this.orders = orders;
        this.orderPayments = orderPayments;
        this.properties = properties;
        this.wechat = wechat;
        this.wechatClient = wechatClient;
        this.inventoryClient = inventoryClient;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    @Scheduled(scheduler = "orderPaymentScheduler", fixedDelayString = "${order.payment-work.scan-delay}",
            initialDelayString = "${order.payment-work.scan-delay}")
    @Transactional(propagation = Propagation.NEVER)
    public void scan() {
        if (!properties.scanEnabled()) return;
        if (wechat.enabled()) {
            scan(Kind.PAYMENT);
            scan(Kind.REFUND);
        }
        if (properties.inventoryReleaseEnabled()) scan(Kind.RELEASE);
    }

    private void scan(Kind kind) {
        for (var work : payments.due(kind, nextAt(Duration.ZERO), properties.batchSize())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                run(work);
            } catch (RuntimeException failure) {
                // An unavailable database leaves the claim for expiry. Other rows can still progress.
                LOG.warn("Payment work persistence failed orderId={} kind={} type={}",
                        work.orderId(), work.kind(), failure.getClass().getSimpleName());
            }
        }
    }

    private void run(PaymentWork work) {
        var now = nextAt(Duration.ZERO);
        var token = UUID.randomUUID().toString();
        if (payments.claim(work, token, now, now.plus(properties.lease()).truncatedTo(ChronoUnit.MICROS)) != 1) return;
        if (!now.isBefore(work.stopAt()) || work.attempts() >= properties.maxFailures()) {
            review(work, token, "WORK_BUDGET_EXHAUSTED");
            return;
        }

        try {
            switch (work.kind()) {
                case PAYMENT -> reconcilePayment(work, token, wechatClient.getObject());
                case REFUND -> reconcileRefund(work, token, wechatClient.getObject());
                case RELEASE -> releaseInventory(work, token);
            }
        } catch (RemoteFailure failure) {
            if (failure.retryable() && work.attempts() + 1 < properties.maxFailures()) {
                payments.finish(work, token, "READY", work.attempts() + 1,
                        nextAt(properties.retryDelay()), failure.code());
            } else {
                review(work, token, failure.code());
            }
        }
    }

    private void reconcilePayment(PaymentWork work, String token, IWechatPayClient client) {
        var id = work.orderId();
        var payment = payments.find(id);
        var order = orders.find(id);
        if (!"PENDING".equals(payment.paymentState())) {
            finish(work, token, "DONE", Duration.ZERO, null, () -> { });
            return;
        }

        Trade trade = queryTrade(client, payment);
        if (trade != null && (!payment.tradeNo().equals(trade.tradeNo()) || !payment.appId().equals(trade.appId())
                || !payment.merchantId().equals(trade.merchantId()))) {
            throw new RemoteFailure("WECHAT_TRADE_MISMATCH", false);
        }
        var state = trade == null ? "NOT_FOUND" : trade.state();
        if ("SUCCESS".equals(state)) {
            try {
                orderPayments.recordTrade(trade);
            } catch (ResponseStatusException invalid) {
                throw new RemoteFailure("WECHAT_TRADE_MISMATCH", false);
            }
            return;
        }
        if ("CLOSED".equals(state)) {
            finish(work, token, "DONE", Duration.ZERO, null, () -> payments.closePayment(id));
            return;
        }
        if (!"NOT_FOUND".equals(state) && !"NOTPAY".equals(state) && !"USERPAYING".equals(state)
                && !"PAYERROR".equals(state)) {
            throw new RemoteFailure("WECHAT_UNEXPECTED_TRADE_STATE", false);
        }

        var expiresAt = order.expiresAt();
        boolean expired = !order.state().equals("PENDING") || !Instant.now().isBefore(expiresAt);
        if (expired) {
            if (trade != null && !closeTrade(client, payment)) {
                finish(work, token, "READY", properties.retryDelay(), null, () -> { });
                return;
            }
            if (trade == null && payment.codeUrl() != null) throw new RemoteFailure("WECHAT_TRADE_NOT_FOUND", true);
            finish(work, token, "DONE", Duration.ZERO, null, () -> payments.closePayment(id));
            return;
        }

        // WeChat may extend an expiry less than one minute away. Leave room for the request deadline.
        if (payment.codeUrl() == null && ("NOT_FOUND".equals(state) || "NOTPAY".equals(state))
                && expiresAt.isAfter(Instant.now().plusSeconds(61).plus(wechat.requestTimeout()))) {
            String codeUrl;
            try {
                codeUrl = client.prepay(payment, expiresAt);
            } catch (RemoteFailure failure) {
                if (!failure.code().equals("WECHAT_OUT_TRADE_NO_USED")) throw failure;
                // Keep reconciling the original trade when a previously submitted prepay cannot return a QR URL.
                finish(work, token, "READY", properties.paymentPollDelay(), failure.code(), () -> { });
                return;
            }
            if (codeUrl == null || !codeUrl.startsWith("weixin://wxpay/") || codeUrl.length() > 1024) {
                throw new RemoteFailure("WECHAT_INVALID_CODE_URL", true);
            }
            finish(work, token, "READY", properties.paymentPollDelay(), null, () -> payments.saveCodeUrl(id, codeUrl));
        } else {
            finish(work, token, "READY", properties.paymentPollDelay(), null, () -> { });
        }
    }

    private Trade queryTrade(IWechatPayClient client, PaymentOrder payment) {
        try {
            return client.query(payment);
        } catch (RemoteFailure failure) {
            if (failure.code().equals("WECHAT_ORDER_NOT_EXIST") || failure.code().equals("WECHAT_ORDER_NOT_EXISTS")) return null;
            throw failure;
        }
    }

    private boolean closeTrade(IWechatPayClient client, PaymentOrder payment) {
        try {
            client.close(payment);
            return true;
        } catch (RemoteFailure failure) {
            return switch (failure.code()) {
                case "WECHAT_ORDER_CLOSED", "WECHAT_ORDERCLOSED" -> true;
                case "WECHAT_ORDER_PAID", "WECHAT_ORDERPAID" -> false;
                default -> throw failure;
            };
        }
    }

    private void reconcileRefund(PaymentWork work, String token, IWechatPayClient client) {
        var id = work.orderId();
        var payment = payments.find(id);
        IWechatPayClient.Refund refund;
        try {
            refund = client.queryRefund(payment);
        } catch (RemoteFailure failure) {
            if (!failure.code().equals("WECHAT_RESOURCE_NOT_EXISTS") && !failure.code().equals("WECHAT_REFUND_NOT_EXIST")) {
                throw failure;
            }
            if (!"PENDING".equals(payment.refundState())) throw new RemoteFailure("WECHAT_REFUND_NOT_VISIBLE", true);
            // A lost response is followed by another query. Any resubmission uses the original refund number.
            client.refund(payment);
            finish(work, token, "READY", properties.refundPollDelay(), null,
                    () -> payments.recordRefund(id, "PROCESSING", null));
            return;
        }
        if (refund == null || !payment.refundNo().equals(refund.refundNo()) || !payment.tradeNo().equals(refund.tradeNo())
                || !payment.transactionId().equals(refund.transactionId()) || refund.refundId() == null
                || !refund.refundId().matches("[0-9]{1,32}")
                || (payment.refundId() != null && !payment.refundId().equals(refund.refundId()))
                || !Objects.equals((long) payment.amountCents(), refund.amountCents())
                || !Objects.equals((long) payment.amountCents(), refund.totalCents()) || !"CNY".equals(refund.currency())
                || refund.state() == null) {
            throw new RemoteFailure("REFUND_MISMATCH", false);
        }
        switch (refund.state()) {
            case "SUCCESS" -> finish(work, token, "DONE", Duration.ZERO, null,
                    () -> payments.recordRefund(id, "SUCCESS", refund.refundId()));
            case "PROCESSING" -> finish(work, token, "READY", refundPollDelay(work), null,
                    () -> payments.recordRefund(id, "PROCESSING", refund.refundId()));
            case "CLOSED", "ABNORMAL" -> finish(work, token, "REVIEW", Duration.ZERO, "REFUND_" + refund.state(),
                    () -> payments.recordRefund(id, refund.state(), refund.refundId()));
            default -> throw new RemoteFailure("REFUND_MISMATCH", false);
        }
    }

    private Duration refundPollDelay(PaymentWork work) {
        var trackingStart = work.stopAt().minus(properties.reconciliationWindow());
        return Instant.now().isBefore(trackingStart.plus(Duration.ofMinutes(5)))
                ? properties.refundPollDelay() : properties.refundPollDelay().multipliedBy(10);
    }

    private void releaseInventory(PaymentWork work, String token) {
        var order = orders.find(work.orderId());
        if (!"CLOSED".equals(order.state())) throw new RemoteFailure("INVENTORY_RELEASE_ORDER_NOT_CLOSED", false);
        var eventId = "order-release-" + order.id();
        inventoryClient.getObject().release(new Release(eventId, order.id(), order.skuId(), order.quantity(), order.closeReason()));
        finish(work, token, "DONE", Duration.ZERO, null, () -> { });
    }

    private void finish(PaymentWork work, String token, String state, Duration delay, String error, Runnable persist) {
        transactions.executeWithoutResult(status -> {
            // Keep the same lock order as callback and timeout close. A stale claim cannot write local results.
            orders.lock(work.orderId());
            if (payments.finish(work, token, state, 0, nextAt(delay), error) == 1) persist.run();
        });
    }

    private void review(PaymentWork work, String token, String error) {
        if (payments.finish(work, token, "REVIEW", work.attempts() + 1, nextAt(Duration.ZERO), error) == 1) {
            LOG.warn("Payment work requires review orderId={} kind={} code={}", work.orderId(), work.kind(), error);
        }
    }

    private static Instant nextAt(Duration delay) {
        return Instant.now().plus(delay).truncatedTo(ChronoUnit.MICROS);
    }
}

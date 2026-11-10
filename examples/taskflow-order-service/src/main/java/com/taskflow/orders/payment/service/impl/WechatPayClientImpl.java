package com.taskflow.orders.payment.service.impl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import com.taskflow.orders.payment.config.PaymentHttp;
import com.taskflow.orders.payment.config.PaymentWorkProperties;
import com.taskflow.orders.payment.config.WechatPaymentProperties;
import com.taskflow.orders.config.OrderProperties;
import com.taskflow.orders.payment.controller.OrderPaymentController;
import com.taskflow.orders.payment.dto.PaymentProtocol.Notification;
import com.taskflow.orders.payment.model.PaymentOrder;
import com.taskflow.orders.payment.service.IWechatPayClient;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.exception.WechatPayException;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(prefix = "order.wechat-pay", name = "enabled", havingValue = "true")
public class WechatPayClientImpl implements IWechatPayClient {
    private final WechatPaymentProperties properties;
    private final NativePayService nativePay;
    private final RefundService refunds;
    private final NotificationParser notifications;
    private final JsonMapper json;

    public WechatPayClientImpl(WechatPaymentProperties properties, PaymentWorkProperties work,
                               OrderProperties orders, JsonMapper json) {
        PaymentHttp.requireHttps(properties.notifyUrl(), OrderPaymentController.WECHAT_CALLBACK_PATH);
        if (properties.appId() == null || !properties.appId().matches("[A-Za-z0-9]{1,32}")
                || properties.merchantId() == null || !properties.merchantId().matches("[0-9]{1,32}")
                || properties.apiV3Key() == null || properties.apiV3Key().getBytes(StandardCharsets.UTF_8).length != 32
                || properties.notifyUrl().toString().length() > 255
                || orders.paymentWindow().compareTo(java.time.Duration.ofMinutes(2)) < 0
                || orders.paymentWindow().compareTo(java.time.Duration.ofHours(2)) > 0
                || properties.connectTimeout().isNegative() || properties.connectTimeout().isZero()
                || properties.requestTimeout().isNegative() || properties.requestTimeout().isZero()
                || work.lease().compareTo(properties.requestTimeout().multipliedBy(2)) <= 0) {
            throw new IllegalArgumentException("Invalid WeChat merchant or timeout configuration");
        }
        this.properties = properties;
        this.json = json;
        var config = new RSAPublicKeyConfig.Builder().merchantId(properties.merchantId())
                .merchantSerialNumber(properties.merchantSerial()).privateKeyFromPath(properties.privateKeyPath())
                .publicKeyId(properties.publicKeyId()).publicKeyFromPath(properties.publicKeyPath())
                .apiV3Key(properties.apiV3Key()).build();
        var transport = new DefaultHttpClientBuilder().config(config)
                .okHttpClient(PaymentHttp.client(properties.connectTimeout(), properties.requestTimeout()))
                .disableRetryOnConnectionFailure().build();
        nativePay = new NativePayService.Builder().httpClient(transport).build();
        refunds = new RefundService.Builder().httpClient(transport).build();
        notifications = new NotificationParser(config);
    }

    @Override
    public String prepay(PaymentOrder payment, Instant expiresAt) {
        var request = new PrepayRequest();
        request.setAppid(payment.appId());
        request.setMchid(payment.merchantId());
        request.setOutTradeNo(payment.tradeNo());
        request.setDescription(payment.description());
        request.setTimeExpire(DateTimeFormatter.ISO_INSTANT.format(expiresAt.truncatedTo(ChronoUnit.SECONDS)));
        request.setNotifyUrl(properties.notifyUrl().toString());
        var amount = new Amount();
        amount.setTotal(payment.amountCents());
        amount.setCurrency("CNY");
        request.setAmount(amount);
        return call(payment, () -> nativePay.prepay(request).getCodeUrl());
    }

    @Override
    public Trade query(PaymentOrder payment) {
        var request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(payment.merchantId());
        request.setOutTradeNo(payment.tradeNo());
        return call(payment, () -> trade(nativePay.queryOrderByOutTradeNo(request)));
    }

    @Override
    public void close(PaymentOrder payment) {
        var request = new CloseOrderRequest();
        request.setMchid(payment.merchantId());
        request.setOutTradeNo(payment.tradeNo());
        call(payment, () -> { nativePay.closeOrder(request); return null; });
    }

    @Override
    public void refund(PaymentOrder payment) {
        var request = new CreateRequest();
        request.setTransactionId(payment.transactionId());
        request.setOutRefundNo(payment.refundNo());
        request.setReason("订单已关闭或超过付款期限");
        var amount = new AmountReq();
        amount.setTotal((long) payment.amountCents());
        amount.setRefund((long) payment.amountCents());
        amount.setCurrency("CNY");
        request.setAmount(amount);
        call(payment, () -> refunds.create(request));
    }

    @Override
    public Refund queryRefund(PaymentOrder payment) {
        var request = new QueryByOutRefundNoRequest();
        request.setOutRefundNo(payment.refundNo());
        return call(payment, () -> {
            var result = refunds.queryByOutRefundNo(request);
            var amount = result.getAmount();
            return new Refund(result.getOutRefundNo(), result.getOutTradeNo(), result.getTransactionId(),
                    result.getRefundId(), name(result.getStatus()), amount == null ? null : amount.getRefund(),
                    amount == null ? null : amount.getTotal(), amount == null ? null : amount.getCurrency());
        });
    }

    @Override
    public Trade decode(Notification notification) {
        try {
            var time = Instant.ofEpochSecond(Long.parseLong(notification.timestamp()));
            var now = Instant.now();
            if (time.isBefore(now.minusSeconds(300)) || time.isAfter(now.plusSeconds(300))) {
                throw new IllegalArgumentException("Stale notification");
            }
            var request = new RequestParam.Builder().serialNumber(notification.serial())
                    .timestamp(notification.timestamp()).nonce(notification.nonce())
                    .signature(notification.signature()).body(notification.body()).build();
            var transaction = notifications.parse(request, Transaction.class);
            if (!"TRANSACTION.SUCCESS".equals(json.readTree(notification.body()).path("event_type").asString(""))) {
                throw new IllegalArgumentException("Unexpected notification event");
            }
            return trade(transaction);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "WECHAT_NOTIFICATION_INVALID");
        }
    }

    private static Trade trade(Transaction transaction) {
        var amount = transaction.getAmount();
        var successTime = transaction.getSuccessTime();
        return new Trade(transaction.getAppid(), transaction.getMchid(), transaction.getOutTradeNo(),
                transaction.getTransactionId(), name(transaction.getTradeType()), name(transaction.getTradeState()),
                successTime == null ? null : OffsetDateTime.parse(successTime).toInstant(),
                amount == null ? null : amount.getTotal(), amount == null ? null : amount.getCurrency());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <T> T call(PaymentOrder payment, Supplier<T> request) {
        if (!properties.merchantId().equals(payment.merchantId()) || !properties.appId().equals(payment.appId())) {
            throw new RemoteFailure("WECHAT_MERCHANT_CHANGED", false);
        }
        try {
            return request.get();
        } catch (ServiceException response) {
            int status = response.getHttpStatusCode();
            String code = response.getErrorCode();
            if (code == null || !code.matches("[A-Z0-9_]{1,48}")) code = "HTTP_" + status;
            throw new RemoteFailure("WECHAT_" + code, status == 408 || status == 429 || status >= 500);
        } catch (WechatPayException failure) {
            throw new RemoteFailure("WECHAT_UNCONFIRMED", true);
        }
    }
}

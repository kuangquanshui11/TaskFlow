package com.taskflow.orders.payment.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.taskflow.orders.payment.dto.PaymentProtocol.*;
import com.taskflow.orders.payment.service.IOrderPaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/order-payments")
public class OrderPaymentController {
    public static final String WECHAT_CALLBACK_PATH = "/api/v1/order-payments/updateWechatNotification";
    private static final int MAX_NOTIFICATION_BYTES = 64 * 1024;
    private final IOrderPaymentService payments;

    public OrderPaymentController(IOrderPaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/insertNative")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Status insert(@Valid @RequestBody Start request) {
        return payments.insert(request.orderId().toString());
    }

    @GetMapping("/select")
    public Status select(@RequestParam UUID orderId) {
        return payments.select(orderId.toString());
    }

    @PostMapping("/updateRetry")
    public Status retry(@Valid @RequestBody Retry request) {
        return payments.retry(request.orderId().toString(), request.kind());
    }

    @PostMapping("/updateWechatNotification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void notifyPayment(@RequestHeader("Wechatpay-Serial") String serial,
                              @RequestHeader("Wechatpay-Timestamp") String timestamp,
                              @RequestHeader("Wechatpay-Nonce") String nonce,
                              @RequestHeader("Wechatpay-Signature") String signature,
                              HttpServletRequest request) throws IOException {
        var bytes = request.getInputStream().readNBytes(MAX_NOTIFICATION_BYTES + 1);
        if (bytes.length > MAX_NOTIFICATION_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        payments.notifyPayment(new Notification(serial, timestamp, nonce, signature, new String(bytes, StandardCharsets.UTF_8)));
    }
}

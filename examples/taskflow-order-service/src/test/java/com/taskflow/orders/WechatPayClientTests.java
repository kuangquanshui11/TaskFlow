package com.taskflow.orders;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.orders.config.OrderProperties;
import com.taskflow.orders.payment.config.PaymentHttp;
import com.taskflow.orders.payment.config.PaymentWorkProperties;
import com.taskflow.orders.payment.config.WechatPaymentProperties;
import com.taskflow.orders.payment.dto.PaymentProtocol.Notification;
import com.taskflow.orders.payment.service.impl.WechatPayClientImpl;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class WechatPayClientTests {
    private static final String API_KEY = "0123456789abcdef0123456789abcdef";
    private static final String PUBLIC_KEY_ID = "PUB_KEY_ID_123456789";
    private static final String NONCE = "abcdefghijkl";
    private static final String AAD = "transaction";
    @TempDir Path files;

    @Test
    void sdkValidatesSignatureAndDecryptsOriginalNotification() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var merchant = generator.generateKeyPair();
        var platform = generator.generateKeyPair();
        var json = JsonMapper.builder().build();
        var properties = new WechatPaymentProperties(true, "wxTest", "123456", "0123456789ABCDEF",
                pem("merchant.pem", "PRIVATE KEY", merchant.getPrivate()), PUBLIC_KEY_ID,
                pem("wechat.pem", "PUBLIC KEY", platform.getPublic()), API_KEY,
                URI.create("https://orders.example.test/api/v1/order-payments/updateWechatNotification"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        var work = new PaymentWorkProperties(false, null, null, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1),
                Duration.ofDays(8), 8, 10, true);
        var order = new OrderProperties("api", "worker", Duration.ofMinutes(30), false,
                Duration.ofSeconds(30), Duration.ofSeconds(5), 10);
        var client = new WechatPayClientImpl(properties, work, order, json);
        String trade = """
            {"appid":"wxTest","mchid":"123456","out_trade_no":"31c94f03a1334245844c75346f8fcdf1",
             "transaction_id":"123456789","trade_type":"NATIVE","trade_state":"SUCCESS",
             "success_time":"2026-11-10T10:00:00+08:00","amount":{"total":250,"currency":"CNY"}}
            """;
        var body = json.writeValueAsString(Map.of("id", "notification-id", "event_type", "TRANSACTION.SUCCESS",
                "resource_type", "encrypt-resource", "resource", Map.of("algorithm", "AEAD_AES_256_GCM",
                        "original_type", "transaction", "nonce", NONCE, "associated_data", AAD, "ciphertext", encrypt(trade))));
        var now = Instant.now().getEpochSecond();
        var signed = signed(platform.getPrivate(), body, now);
        assertThat(client.decode(signed).amountCents()).isEqualTo(250);
        assertThat(client.decode(signed).paidAt()).isEqualTo(Instant.parse("2026-11-10T02:00:00Z"));

        var modified = new Notification(signed.serial(), signed.timestamp(), signed.nonce(), signed.signature(), body + " ");
        assertThatThrownBy(() -> client.decode(modified)).hasMessageContaining("WECHAT_NOTIFICATION_INVALID");
        assertThatThrownBy(() -> client.decode(signed(platform.getPrivate(), body, now - 301)))
                .hasMessageContaining("WECHAT_NOTIFICATION_INVALID");
        var wrongKey = signed(merchant.getPrivate(), body, now);
        assertThatThrownBy(() -> client.decode(wrongKey)).hasMessageContaining("WECHAT_NOTIFICATION_INVALID");
    }

    @Test
    void outboundPostIsSentOnceEvenWhenServerInvitesRetry() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test", exchange -> {
            calls.incrementAndGet();
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(503, -1);
            }
        });
        server.start();
        try {
            var client = PaymentHttp.client(Duration.ofSeconds(1), Duration.ofSeconds(2));
            var request = new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/test")
                    .post(RequestBody.create("test", null)).build();
            try (var response = client.newCall(request).execute()) {
                assertThat(response.code()).isEqualTo(503);
            }
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    private String pem(String file, String type, Key key) throws Exception {
        var path = files.resolve(file);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + Base64.getEncoder().encodeToString(key.getEncoded())
                + "\n-----END " + type + "-----\n");
        return path.toString();
    }

    private static String encrypt(String text) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(bytes(API_KEY), "AES"), new GCMParameterSpec(128, bytes(NONCE)));
        cipher.updateAAD(bytes(AAD));
        return Base64.getEncoder().encodeToString(cipher.doFinal(bytes(text)));
    }

    private static Notification signed(PrivateKey key, String body, long timestamp) throws Exception {
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(bytes(timestamp + "\n" + NONCE + "\n" + body + "\n"));
        return new Notification(PUBLIC_KEY_ID, Long.toString(timestamp), NONCE,
                Base64.getEncoder().encodeToString(signature.sign()), body);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

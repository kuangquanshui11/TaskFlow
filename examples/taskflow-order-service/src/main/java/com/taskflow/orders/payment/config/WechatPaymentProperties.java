package com.taskflow.orders.payment.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order.wechat-pay")
public record WechatPaymentProperties(boolean enabled, String appId, String merchantId, String merchantSerial,
                                      String privateKeyPath, String publicKeyId, String publicKeyPath,
                                      String apiV3Key, URI notifyUrl, Duration connectTimeout, Duration requestTimeout) { }

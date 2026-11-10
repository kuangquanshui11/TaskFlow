package com.taskflow.orders.inventory.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order.inventory-sync")
public record InventorySyncProperties(String wmsSourceId, URI wmsUrl, String wmsToken,
                                      Duration wmsConnectTimeout, Duration wmsRequestTimeout,
                                      boolean wmsAllowLoopbackHttp) { }

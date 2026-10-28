package com.taskflow.admin.access.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.access.session")
public record SessionProperties(Duration idleTimeout, Duration loginWindow, Duration rotationGrace, String keyPrefix) {
    public SessionProperties {
        if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()
                || loginWindow == null || loginWindow.isNegative() || loginWindow.isZero()
                || idleTimeout.compareTo(loginWindow) <= 0
                || rotationGrace == null || rotationGrace.isNegative()
                || keyPrefix == null || !keyPrefix.matches("[A-Za-z0-9:_-]{1,120}")) {
            throw new IllegalArgumentException("Session idle timeout must exceed the positive login window; configure an isolated Redis prefix");
        }
    }
}

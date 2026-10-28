package com.taskflow.gateway.access.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.access")
public record AccessProperties(String issuer, String audience, String publicKeyLocation) {
    public AccessProperties {
        if (issuer == null || !issuer.matches("https?://[^\\s]+/realms/[^/\\s]+") ||
                audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Configure taskflow.access issuer (/realms/{realm}) and audience");
        }
    }
    public String jwkSetUri() { return issuer + "/protocol/openid-connect/certs"; }
}

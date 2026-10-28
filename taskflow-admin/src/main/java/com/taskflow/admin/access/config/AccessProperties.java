package com.taskflow.admin.access.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.access")
public record AccessProperties(String issuer, String audience, String clientId, String publicKeyLocation) {
    public AccessProperties {
        if (issuer == null || !issuer.matches("https?://[^\\s]+/realms/[^/\\s]+") ||
                audience == null || audience.isBlank() || clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Configure taskflow.access issuer (/realms/{realm}), audience and client-id");
        }
    }
    public String jwkSetUri() { return issuer + "/protocol/openid-connect/certs"; }
}

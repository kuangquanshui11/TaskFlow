package com.taskflow.admin.access.dto;

import java.util.Set;

public final class AccessResponses {
    private AccessResponses() {}
    public record Configuration(String issuer, String clientId) {}
    public record Current(String subject, String username, Set<String> roles, Set<String> permissions) {}
}

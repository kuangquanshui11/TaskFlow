package com.taskflow.admin.access.model;

import java.util.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AccessPolicy {
    private AccessPolicy() {}
    private static final Set<String> READ = Set.of("job:read", "run:read", "worker:read");
    private static final Set<String> OPERATE = Set.of("job:operate", "run:trigger", "job-sync:retry", "job-sync:reconcile", "job-sync:repair");
    private static final Set<String> WRITE = Set.of("job:write", "job:delete");

    public static Set<String> roles(Jwt jwt, String audience) {
        // Only this API's client roles grant business permissions; realm roles and arbitrary scopes do not.
        Object clients = jwt.getClaims().get("resource_access");
        if (!(clients instanceof Map<?, ?> resources) || !(resources.get(audience) instanceof Map<?, ?> client)
                || !(client.get("roles") instanceof Collection<?> roles)) return Set.of();
        var result = new TreeSet<String>();
        for (Object role : roles) if (role instanceof String value && Set.of("VIEWER", "OPERATOR", "ADMIN").contains(value)) result.add(value);
        return Collections.unmodifiableSet(result);
    }
    public static Set<String> permissions(Set<String> roles) {
        var result = new TreeSet<String>();
        if (!roles.isEmpty()) result.addAll(READ);
        if (roles.contains("OPERATOR") || roles.contains("ADMIN")) result.addAll(OPERATE);
        if (roles.contains("ADMIN")) result.addAll(WRITE);
        return Collections.unmodifiableSet(result);
    }
    public static Collection<org.springframework.security.core.GrantedAuthority> authorities(Jwt jwt, String audience) {
        return permissions(roles(jwt, audience)).stream().<org.springframework.security.core.GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }
}

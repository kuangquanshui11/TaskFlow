package com.taskflow.orders.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.taskflow.orders.cleanup.config.CleanupProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiAuthenticationFilter extends OncePerRequestFilter {
    private final OrderProperties properties;
    private final CleanupProperties cleanup;

    public ApiAuthenticationFilter(OrderProperties properties, CleanupProperties cleanup) {
        this.properties = properties;
        this.cleanup = cleanup;
        if (cleanup.enabled() && (cleanup.token() == null || cleanup.token().isBlank()
                || cleanup.token().equals(properties.apiToken()) || cleanup.token().equals(properties.workerToken()))) {
            throw new IllegalStateException("Cleanup requires a separate token");
        }
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/actuator/health")) { chain.doFilter(request, response); return; }
        boolean internal = path.equals("/internal/v1/orders/updateTimeout")
                || path.startsWith("/internal/v1/sales-reports/") || path.startsWith("/internal/v1/inventory-sync/");
        String expected = request.getServletPath().startsWith("/internal/v1/expired-data/")
                ? cleanup.token() : internal ? properties.workerToken() : properties.apiToken();
        String supplied = request.getHeader("X-Taskflow-Token");
        if (expected == null || expected.isBlank() || supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(401); return;
        }
        chain.doFilter(request, response);
    }
}

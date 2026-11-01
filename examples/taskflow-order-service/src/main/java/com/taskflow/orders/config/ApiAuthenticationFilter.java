package com.taskflow.orders.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiAuthenticationFilter extends OncePerRequestFilter {
    private final OrderProperties properties;
    public ApiAuthenticationFilter(OrderProperties properties) { this.properties = properties; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/actuator/health")) { chain.doFilter(request, response); return; }
        boolean internal = path.equals("/internal/v1/orders/updateTimeout");
        String expected = internal ? properties.workerToken() : properties.apiToken();
        String supplied = request.getHeader("X-Taskflow-Token");
        if (expected == null || expected.isBlank() || supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(401); return;
        }
        chain.doFilter(request, response);
    }
}

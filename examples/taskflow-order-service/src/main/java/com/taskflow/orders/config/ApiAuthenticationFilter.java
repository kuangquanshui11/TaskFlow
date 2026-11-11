package com.taskflow.orders.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.taskflow.orders.cleanup.config.CleanupProperties;
import com.taskflow.orders.payment.controller.OrderPaymentController;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiAuthenticationFilter extends OncePerRequestFilter {
    private final OrderProperties properties;
    private final CleanupProperties cleanup;
    private final OrderOperationsProperties operations;

    public ApiAuthenticationFilter(OrderProperties properties, CleanupProperties cleanup,
                                   OrderOperationsProperties operations) {
        this.properties = properties;
        this.cleanup = cleanup;
        this.operations = operations;
        if (cleanup.enabled() && (cleanup.token() == null || cleanup.token().isBlank()
                || cleanup.token().equals(properties.apiToken()) || cleanup.token().equals(properties.workerToken()))) {
            throw new IllegalStateException("Cleanup requires a separate token");
        }
        if (operations.enabled() && (operations.token() == null || operations.token().isBlank()
                || operations.token().equals(properties.apiToken()) || operations.token().equals(properties.workerToken())
                || operations.token().equals(cleanup.token()))) {
            throw new IllegalStateException("Order operations require a separate token");
        }
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/actuator/health") || ("POST".equals(request.getMethod())
                && OrderPaymentController.WECHAT_CALLBACK_PATH.equals(path))) {
            chain.doFilter(request, response);
            return;
        }
        boolean internal = path.equals("/internal/v1/orders/updateTimeout")
                || path.startsWith("/internal/v1/sales-reports/") && !path.equals("/internal/v1/sales-reports/update")
                || path.startsWith("/internal/v1/inventory-sync/");
        String servletPath = request.getServletPath();
        String expected = internal ? properties.workerToken() : properties.apiToken();
        if (servletPath.startsWith("/internal/v1/expired-data/")) {
            expected = cleanup.token();
        } else if (servletPath.startsWith("/internal/v1/order-operations/")) {
            expected = operations.token();
        }
        String supplied = request.getHeader("X-Taskflow-Token");
        if (expected == null || expected.isBlank() || supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(401); return;
        }
        chain.doFilter(request, response);
    }
}

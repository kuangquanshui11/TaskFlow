package com.taskflow.scheduler.execution.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalAuthenticationFilter extends OncePerRequestFilter {
    private final RunProperties properties;
    private final com.taskflow.scheduler.node.config.WorkerNodeProperties nodes;
    public InternalAuthenticationFilter(RunProperties properties,com.taskflow.scheduler.node.config.WorkerNodeProperties nodes) { this.properties = properties; this.nodes=nodes; }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/v1/");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path=request.getRequestURI();
        String expected = path.equals("/internal/v1/attempts/updateResult") || path.equals("/internal/v1/attempts/updateLease") ? properties.reportToken()
                : path.equals("/internal/v1/workers/insert") || path.equals("/internal/v1/workers/updateHeartbeat") ? nodes.nodeToken() : properties.controlToken();
        String supplied = request.getHeader("X-Taskflow-Token");
        if (expected == null || expected.isBlank()) {
            response.setStatus(503); write(response, "SERVICE_NOT_CONFIGURED", "内部服务密钥尚未配置"); return;
        }
        if (supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401); write(response, "UNAUTHORIZED", "内部身份验证失败"); return;
        }
        var requestId = request.getHeader("X-Request-Id");
        if (requestId == null || !requestId.matches("[A-Za-z0-9._-]{1,64}")) requestId = java.util.UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        try (var context = org.slf4j.MDC.putCloseable("requestId", requestId)) {
            chain.doFilter(request, response);
        }
    }
    private void write(HttpServletResponse response, String code, String message) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}

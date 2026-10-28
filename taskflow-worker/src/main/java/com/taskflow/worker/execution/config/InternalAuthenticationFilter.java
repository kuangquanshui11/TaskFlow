package com.taskflow.worker.execution.config;

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
    private final ExecutionProperties properties;
    public InternalAuthenticationFilter(ExecutionProperties properties) { this.properties = properties; }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/v1/");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = properties.dispatchToken();
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

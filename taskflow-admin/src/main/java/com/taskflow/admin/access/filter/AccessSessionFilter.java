package com.taskflow.admin.access.filter;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import com.taskflow.admin.access.exception.AccessSessionException;
import com.taskflow.admin.access.service.IAccessSessionService;
import com.taskflow.admin.system.dto.ApiResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

public class AccessSessionFilter extends OncePerRequestFilter {
    private final IAccessSessionService sessions;
    private final JsonMapper json;
    public AccessSessionFilter(IAccessSessionService sessions, JsonMapper json) { this.sessions = sessions; this.json = json; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean establishesOrRotates = "POST".equals(request.getMethod()) &&
                (path.equals("/api/v1/access/insert") || path.equals("/api/v1/access/update"));
        boolean publicRequest = path.equals("/api/v1/access/selectConfiguration") || path.startsWith("/actuator/health");
        if (!establishesOrRotates && !publicRequest && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth) {
            try { sessions.validateAndTouch(auth.getToken()); }
            catch (AccessSessionException failure) {
                response.setStatus(failure.status());
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                if (failure.status() == 401) response.setHeader("WWW-Authenticate", "Bearer");
                response.getOutputStream().write(json.writeValueAsBytes(ApiResponse.failure(failure.code(), failure.getMessage(), null)));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

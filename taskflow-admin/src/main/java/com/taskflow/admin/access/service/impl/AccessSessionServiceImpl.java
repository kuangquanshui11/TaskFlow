package com.taskflow.admin.access.service.impl;

import java.time.Instant;
import com.taskflow.admin.access.config.SessionProperties;
import com.taskflow.admin.access.exception.AccessSessionException;
import com.taskflow.admin.access.mapper.AccessSessionMapper;
import com.taskflow.admin.access.service.IAccessSessionService;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class AccessSessionServiceImpl implements IAccessSessionService {
    private final AccessSessionMapper sessions;
    private final SessionProperties properties;
    public AccessSessionServiceImpl(AccessSessionMapper sessions, SessionProperties properties) {
        this.sessions = sessions; this.properties = properties;
    }
    @Override public void insert(Jwt jwt) {
        Object value = jwt.getClaims().get("auth_time");
        long authenticatedAt = value instanceof Number number ? number.longValue()
                : value instanceof Instant instant ? instant.getEpochSecond() : 0;
        long now = Instant.now().getEpochSecond();
        // Possessing a refreshed token must not recreate an expired idle session.
        // Only a recent interactive authentication can establish a new session.
        if (authenticatedAt <= 0 || authenticatedAt > now + 60) {
            throw new AccessSessionException(401, "SESSION_REAUTH_REQUIRED", "请重新输入账号密码登录");
        }
        boolean recent = now - authenticatedAt <= properties.loginWindow().toSeconds();
        try {
            if (!sessions.create(key(jwt), jwt.getTokenValue(), authenticatedAt, properties.idleTimeout(), properties.rotationGrace(), recent)) {
                throw new AccessSessionException(401, "SESSION_REAUTH_REQUIRED", "登录会话已过期，请重新输入账号密码登录");
            }
        } catch (DataAccessException failure) { throw unavailable(); }
    }
    @Override public void validateAndTouch(Jwt jwt) {
        guarded(() -> sessions.validateAndTouch(key(jwt), jwt.getTokenValue(), properties.idleTimeout()));
    }
    @Override public void update(Jwt jwt, String previousToken) {
        if (previousToken == null || previousToken.isBlank() || previousToken.length() > 16384) throw expired();
        guarded(() -> sessions.rotate(key(jwt), previousToken, jwt.getTokenValue(), properties.idleTimeout(), properties.rotationGrace()));
    }
    @Override public void disable(Jwt jwt) {
        try { sessions.revoke(key(jwt), properties.idleTimeout()); }
        catch (DataAccessException failure) { throw unavailable(); }
    }
    private void guarded(java.util.function.BooleanSupplier operation) {
        try { if (!operation.getAsBoolean()) throw expired(); }
        catch (DataAccessException failure) { throw unavailable(); }
    }
    private String key(Jwt jwt) {
        return com.taskflow.admin.access.model.SessionIdentity.key(jwt, properties.keyPrefix());
    }
    private AccessSessionException expired() {
        return new AccessSessionException(401, "SESSION_EXPIRED", "登录已失效或已超过30分钟未访问，请重新登录");
    }
    private AccessSessionException unavailable() {
        return new AccessSessionException(503, "SESSION_UNAVAILABLE", "登录会话服务暂时不可用，请稍后重试");
    }
}

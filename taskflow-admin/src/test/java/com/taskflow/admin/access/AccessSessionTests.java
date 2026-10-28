package com.taskflow.admin.access;

import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import com.taskflow.admin.access.config.SessionProperties;
import com.taskflow.admin.access.exception.AccessSessionException;
import com.taskflow.admin.access.mapper.AccessSessionMapper;
import com.taskflow.admin.access.service.impl.AccessSessionServiceImpl;
import com.taskflow.admin.support.TestSessionConfiguration.MemorySessions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessSessionTests {
    private final long authenticatedAt = Instant.now().getEpochSecond();
    private final SessionProperties properties = new SessionProperties(Duration.ofMinutes(30), Duration.ofMinutes(2), Duration.ofSeconds(10), "test:session:");
    private Jwt token(String token) {
        return Jwt.withTokenValue(token).header("alg", "RS256").issuer("https://identity.test/realms/taskflow")
                .subject("user").claim("sid", "session-one").claim("auth_time", authenticatedAt).build();
    }
    @Test void everyValidatedRequestExtendsIdleTimeAndThirtyMinutesWithoutRequestsExpires() {
        var clock = new AtomicLong(0);
        var store = new MemorySessions(clock::get);
        var service = new AccessSessionServiceImpl(store, properties);
        var token = token("signed-token");
        service.insert(token);
        clock.addAndGet(Duration.ofMinutes(29).toMillis()); service.validateAndTouch(token);
        clock.addAndGet(Duration.ofMinutes(29).toMillis()); service.validateAndTouch(token);
        clock.addAndGet(Duration.ofMinutes(30).toMillis());
        assertThatThrownBy(() -> service.validateAndTouch(token)).isInstanceOfSatisfying(AccessSessionException.class,
                error -> assertThat(error.code()).isEqualTo("SESSION_EXPIRED"));
        assertThatThrownBy(() -> service.update(token("refreshed-token"), "signed-token")).isInstanceOf(AccessSessionException.class);
    }
    @Test void rotationHasBoundedGraceAndLogoutRevokesBothTokens() {
        var clock = new AtomicLong(0);
        var service = new AccessSessionServiceImpl(new MemorySessions(clock::get), properties);
        var old = token("old"); var next = token("next");
        service.insert(old); service.update(next, "old");
        service.validateAndTouch(next); service.validateAndTouch(old);
        clock.addAndGet(Duration.ofSeconds(11).toMillis());
        assertThatThrownBy(() -> service.validateAndTouch(old)).isInstanceOf(AccessSessionException.class);
        service.validateAndTouch(next); service.disable(next);
        assertThatThrownBy(() -> service.validateAndTouch(next)).isInstanceOf(AccessSessionException.class);
        assertThatThrownBy(() -> service.insert(next)).isInstanceOf(AccessSessionException.class);
    }
    @Test void anOldInteractiveLoginCannotReopenAnExpiredSessionUsingARefreshedJwt() {
        var service = new AccessSessionServiceImpl(new MemorySessions(System::currentTimeMillis), properties);
        var oldLogin = Jwt.withTokenValue("refreshed").header("alg", "RS256").issuer("https://identity.test/realms/taskflow")
                .subject("user").claim("sid", "sid").claim("auth_time", Instant.now().minusSeconds(1800).getEpochSecond()).build();
        assertThatThrownBy(() -> service.insert(oldLogin)).isInstanceOfSatisfying(AccessSessionException.class,
                error -> assertThat(error.code()).isEqualTo("SESSION_REAUTH_REQUIRED"));
    }
    @Test void redisOutageFailsClosedInsteadOfAuthenticatingFromJwtAlone() {
        var store = mock(AccessSessionMapper.class);
        when(store.validateAndTouch(anyString(), anyString(), any())).thenThrow(new RedisConnectionFailureException("offline"));
        var service = new AccessSessionServiceImpl(store, properties);
        assertThatThrownBy(() -> service.validateAndTouch(token("valid-signed-jwt"))).isInstanceOfSatisfying(AccessSessionException.class,
                error -> { assertThat(error.status()).isEqualTo(503); assertThat(error.code()).isEqualTo("SESSION_UNAVAILABLE"); });
    }
}

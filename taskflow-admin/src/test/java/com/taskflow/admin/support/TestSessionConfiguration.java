package com.taskflow.admin.support;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import com.taskflow.admin.access.mapper.AccessSessionMapper;
import com.taskflow.admin.access.model.SessionIdentity;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.Jwt;

@TestConfiguration(proxyBeanMethods = false)
public class TestSessionConfiguration {
    public static final MemorySessions SESSIONS = new MemorySessions(System::currentTimeMillis);
    @Bean @Primary AccessSessionMapper testSessionMapper() { return SESSIONS; }
    public static void register(String token) {
        try {
            var claims = com.nimbusds.jwt.JWTParser.parse(token).getJWTClaimsSet();
            var jwt = Jwt.withTokenValue(token).header("alg", "RS256").issuer(claims.getIssuer())
                    .subject(claims.getSubject()).claim("sid", claims.getStringClaim("sid")).build();
            SESSIONS.create(SessionIdentity.key(jwt, "taskflow:admin:access:session:v1:"), token,
                    ((Number) claims.getClaim("auth_time")).longValue(), Duration.ofMinutes(30), Duration.ofSeconds(10), true);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    /** Test storage with a controllable clock; never included in the executable application JAR. */
    public static class MemorySessions implements AccessSessionMapper {
        private record Session(String token, String previous, long previousUntil, long authTime, long expiresAt, boolean revoked) {}
        private final Map<String, Session> values = new HashMap<>();
        private final LongSupplier now;
        public MemorySessions(LongSupplier now) { this.now = now; }
        private Session current(String key) {
            var value = values.get(key);
            if (value != null && value.expiresAt <= now.getAsLong()) { values.remove(key); return null; }
            return value;
        }
        public synchronized void expire(String key) { values.remove(key); }
        @Override public synchronized boolean create(String key, String token, long authTime, Duration ttl, Duration grace, boolean allowNew) {
            var old = current(key);
            boolean resume = old != null && !old.revoked && authTime == old.authTime;
            if (!resume && (!allowNew || old != null && authTime <= old.authTime)) return false;
            values.put(key, new Session(token, resume ? old.token : null, now.getAsLong() + grace.toMillis(), authTime, now.getAsLong() + ttl.toMillis(), false));
            return true;
        }
        @Override public synchronized boolean validateAndTouch(String key, String token, Duration ttl) {
            var old = current(key);
            if (old == null || old.revoked || !(old.token.equals(token) || token.equals(old.previous) && now.getAsLong() <= old.previousUntil)) return false;
            values.put(key, new Session(old.token, old.previous, old.previousUntil, old.authTime, now.getAsLong() + ttl.toMillis(), false));
            return true;
        }
        @Override public synchronized boolean rotate(String key, String previous, String token, Duration ttl, Duration grace) {
            var old = current(key);
            if (old == null || old.revoked || !(old.token.equals(previous) || old.token.equals(token))) return false;
            values.put(key, new Session(token, old.token, now.getAsLong() + grace.toMillis(), old.authTime, now.getAsLong() + ttl.toMillis(), false));
            return true;
        }
        @Override public synchronized void revoke(String key, Duration ttl) {
            var old = current(key);
            if (old != null) values.put(key, new Session("", null, 0, old.authTime, now.getAsLong() + ttl.toMillis(), true));
        }
    }
}

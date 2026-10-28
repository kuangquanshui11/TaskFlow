package com.taskflow.admin.access;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.taskflow.admin.access.mapper.redis.RedisAccessSessionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles({"test-redis-connection", "test"})
@EnabledIfEnvironmentVariable(named = "TASKFLOW_REDIS_SESSION_TEST", matches = "true")
class RedisSessionIntegrationTests {
    @Autowired RedisAccessSessionMapper sessions;
    @Autowired StringRedisTemplate redis;
    @Test void jwtIsStoredWithThirtyMinuteSlidingTtlAndExpiredKeysCannotBeRecreated() {
        String key = "taskflow:admin:access:test:" + UUID.randomUUID();
        var idle = Duration.ofMinutes(30);
        try {
            assertThat(sessions.create(key, "old-jwt", 100L, idle, Duration.ofSeconds(10), true)).isTrue();
            assertThat(redis.opsForHash().get(key, "current")).isEqualTo("old-jwt");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1795000L, 1800000L);
            redis.expire(key, Duration.ofSeconds(10));
            assertThat(sessions.validateAndTouch(key, "old-jwt", idle)).isTrue();
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isGreaterThan(1795000L);
            assertThat(sessions.rotate(key, "old-jwt", "new-jwt", idle, Duration.ofSeconds(10))).isTrue();
            assertThat(sessions.validateAndTouch(key, "old-jwt", idle)).isTrue();
            assertThat(sessions.validateAndTouch(key, "new-jwt", idle)).isTrue();
            sessions.revoke(key, idle);
            assertThat(sessions.validateAndTouch(key, "new-jwt", idle)).isFalse();
            assertThat(sessions.create(key, "new-jwt", 100L, idle, Duration.ofSeconds(10), true)).isFalse();
            redis.expire(key, Duration.ofMillis(1));
            await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
            assertThat(sessions.validateAndTouch(key, "new-jwt", idle)).isFalse();
            assertThat(sessions.rotate(key, "new-jwt", "another-jwt", idle, Duration.ZERO)).isFalse();
            assertThat(redis.hasKey(key)).isFalse();
        } finally { redis.delete(key); }
    }
}

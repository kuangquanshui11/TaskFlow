package com.taskflow.gateway;

import java.time.Duration;
import java.util.UUID;
import com.taskflow.gateway.access.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TASKFLOW_RATE_TEST", matches = "true")
class RedisRateLimitIntegrationTests {
    @Autowired ApplicationContext context;
    @Test void independentGatewaysShareQuotaAndSeparateSubjectsAndGroups() {
        var props = new RateLimitProperties("taskflow:test:" + UUID.randomUUID(), 20, 40, 5, 10, 1, 3);
        var first = new SafeRedisRateLimiter(props);
        var second = new SafeRedisRateLimiter(props);
        first.setApplicationContext(context); second.setApplicationContext(context);
        first.getConfig().get(props.namespace() + ":execute").setRequestedTokens(3);
        second.getConfig().get(props.namespace() + ":execute").setRequestedTokens(3);
        assertThat(first.isAllowed("public", "execute:user-one").block(Duration.ofSeconds(3)).isAllowed()).isTrue();
        assertThat(second.isAllowed("public", "execute:user-one").block(Duration.ofSeconds(3)).isAllowed()).isFalse();
        assertThat(second.isAllowed("public", "execute:user-two").block(Duration.ofSeconds(3)).isAllowed()).isTrue();
        assertThat(second.isAllowed("public", "read:user-one").block(Duration.ofSeconds(3)).isAllowed()).isTrue();
        // The token bucket script expires its own isolated test keys; never FLUSHDB or scan business keys.
    }
}

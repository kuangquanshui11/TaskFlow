package com.taskflow.admin;

import javax.sql.DataSource;

import com.taskflow.admin.support.IsolatedH2ContextInitializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in, read-only check against the configured Redis; SQL always uses test H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test-redis-connection", "test"})
@ContextConfiguration(initializers = IsolatedH2ContextInitializer.class)
@EnabledIfEnvironmentVariable(named = "TASKFLOW_REDIS_CONNECTION_TEST", matches = "true")
@org.springframework.security.test.context.support.WithMockUser(authorities = {"job:write", "job:delete", "job:operate", "run:trigger", "job-sync:retry"})
@org.springframework.context.annotation.Import(com.taskflow.admin.support.TestSessionConfiguration.class)
class RedisConnectionIntegrationTests {
    @Autowired
    private RedisConnectionFactory connections;
    @Autowired
    private DataSource dataSource;

    @Test
    void authenticatesAndPingsUsingApplicationConnectionSettings() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:taskflow-admin-");
        }
        try (var connection = connections.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }
}

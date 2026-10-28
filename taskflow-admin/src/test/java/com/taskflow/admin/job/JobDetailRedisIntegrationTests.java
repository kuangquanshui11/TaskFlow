package com.taskflow.admin.job;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.job.service.IJobDetailService;
import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import com.taskflow.admin.job.model.JobDefinition.MisfirePolicy;
import com.taskflow.admin.job.model.JobDefinition.ScheduleType;
import com.taskflow.admin.job.mapper.JobDataMapper;
import com.taskflow.admin.job.config.JobDetailCacheProperties;
import com.taskflow.admin.support.IsolatedH2ContextInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 只访问本次随机命名空间内的精确 key，不扫描、不清空 Redis，不写用户 MySQL。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test-redis-connection", "test", "test-redis-cache"})
@ContextConfiguration(initializers = IsolatedH2ContextInitializer.class)
@EnabledIfEnvironmentVariable(named = "TASKFLOW_REDIS_CACHE_TEST", matches = "true")
class JobDetailRedisIntegrationTests {
    @Autowired private IJobService jobs;
    @Autowired private IJobDetailService details;
    @Autowired private JobDetailCacheProperties properties;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private JobDataMapper repository;
    private final Set<String> ownedKeys = new HashSet<>();

    @BeforeEach
    void requireIsolatedResources() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:taskflow-admin-");
        }
        assertThat(properties.keyPrefix()).matches("taskflow:admin:it:detail:[0-9a-f-]{36}:");
        assertThat(properties.ttl()).isEqualTo(Duration.ofSeconds(2));
    }

    @AfterEach
    void removeOnlyOwnedKeys() {
        for (var key : ownedKeys) {
            redis.delete(key);
        }
        ownedKeys.clear();
    }

    @Test
    void realRedisSupportsFillHitExpiryAndDatabaseReload() {
        var job = jobs.insert(configuration("Redis 真实过期"));
        var key = track(job.id());
        clearInvocations(repository);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(details.selectById(job.id())).isEqualTo(job);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);
        assertThat(details.selectById(job.id())).isEqualTo(job);
        verify(repository, times(1)).findById(job.id());
        await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.FALSE.equals(redis.hasKey(key)));
        assertThat(details.selectById(job.id())).isEqualTo(job);
        verify(repository, times(2)).findById(job.id());
    }

    @Test
    void realRedisInvalidatesAfterUpdateEnableDisableAndDelete() {
        var job = jobs.insert(configuration("Redis 生命周期"));
        var key = track(job.id());
        details.selectById(job.id());
        var updated = jobs.update(job.id(), 1, configuration("Redis 已更新"));
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(details.selectById(job.id())).isEqualTo(updated);
        var enabled = jobs.changeEnabled(job.id(), 2, true);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(details.selectById(job.id())).isEqualTo(enabled);
        var disabled = jobs.changeEnabled(job.id(), 3, false);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(details.selectById(job.id())).isEqualTo(disabled);
        jobs.delete(job.id(), 4);
        assertThat(redis.hasKey(key)).isFalse();
        assertThatThrownBy(() -> details.selectById(job.id())).isInstanceOf(JobException.class);
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void realRedisCorruptValueIsReplacedWithFreshDatabaseData() {
        var job = jobs.insert(configuration("Redis 坏缓存"));
        var key = track(job.id());
        redis.opsForValue().set(key, "{invalid", properties.ttl());
        assertThat(details.selectById(job.id())).isEqualTo(job);
        assertThat(redis.opsForValue().get(key)).contains("expiresAt", "Redis 坏缓存");
    }

    private String track(long id) {
        var key = properties.keyPrefix() + id;
        ownedKeys.add(key);
        return key;
    }

    private Configuration configuration(String name) {
        return new Configuration(name, null, "demoSuccess", "{\"nested\":{\"enabled\":true}}", ScheduleType.MANUAL,
                null, "Asia/Shanghai", false, 60, 3, 10, MisfirePolicy.SKIP);
    }
}

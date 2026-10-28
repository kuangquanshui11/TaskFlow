package com.taskflow.admin.job;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.job.service.IJobDetailService;
import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import com.taskflow.admin.job.model.JobDefinition.MisfirePolicy;
import com.taskflow.admin.job.model.JobDefinition.ScheduleType;
import com.taskflow.admin.job.mapper.JobDataMapper;
import com.taskflow.admin.job.model.JobFilter;
import com.taskflow.admin.job.config.JobDetailCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "test-cache"})
@org.springframework.security.test.context.support.WithMockUser(authorities = {"job:write", "job:delete", "job:operate", "run:trigger", "job-sync:retry"})
@org.springframework.context.annotation.Import(com.taskflow.admin.support.TestSessionConfiguration.class)
class JobDetailCacheIntegrationTests {
    @Autowired private IJobService jobs;
    @Autowired private IJobDetailService details;
    @Autowired private JobDetailCacheProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JsonMapper json;
    @Value("${local.server.port}") private int port;
    @MockitoBean private StringRedisTemplate redis;
    @MockitoSpyBean private JobDataMapper repository;
    private final Map<String, String> stored = new ConcurrentHashMap<>();
    private ValueOperations<String, String> values;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:taskflow-admin-");
        }
        jdbc.update("DELETE FROM job_definition");
        stored.clear();
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return stored.get(call.getArgument(0, String.class));
        });
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            stored.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(call -> stored.remove(call.getArgument(0, String.class)) != null);
    }

    @Test
    void httpDetailMissFillsAndSecondRequestHitsWithoutAnotherDatabaseRead() throws Exception {
        var job = jobs.insert(configuration("详情"));
        clearInvocations(repository);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/api/v1/jobs/selectById?id=" + job.id())).header("Authorization", "Bearer " + com.taskflow.admin.support.TestTokens.admin()).timeout(Duration.ofSeconds(10)).GET().build();
            var first = client.send(request, HttpResponse.BodyHandlers.ofString());
            var second = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(json.readTree(second.body()).get("data")).isEqualTo(json.readTree(first.body()).get("data"));
            assertThat(stored).containsKey(key(job));
            verify(repository, times(1)).findById(job.id());
        }
    }

    @Test
    void updateEnableDisableAndDeleteInvalidateAndReturnAuthoritativeData() {
        var job = jobs.insert(configuration("旧名称"));
        details.selectById(job.id());
        var updated = jobs.update(job.id(), 1, configuration("新名称"));
        assertThat(updated.configuration().name()).isEqualTo("新名称");
        assertThat(updated.configVersion()).isEqualTo(2);
        assertThat(stored).doesNotContainKey(key(job));
        assertThat(details.selectById(job.id())).isEqualTo(updated);

        var enabled = jobs.changeEnabled(job.id(), 2, true);
        assertThat(stored).doesNotContainKey(key(job));
        assertThat(details.selectById(job.id())).isEqualTo(enabled);
        var disabled = jobs.changeEnabled(job.id(), 3, false);
        assertThat(stored).doesNotContainKey(key(job));
        assertThat(details.selectById(job.id())).isEqualTo(disabled);
        jobs.delete(job.id(), 4);
        assertThat(stored).doesNotContainKey(key(job));
        assertThatThrownBy(() -> details.selectById(job.id())).isInstanceOf(JobException.class);
        assertThat(stored).doesNotContainKey(key(job));
    }

    @Test
    void evictionOnlyRunsAfterTheNewVersionIsVisibleToAnotherConnection() {
        var job = jobs.insert(configuration("提交前"));
        details.selectById(job.id());
        doAnswer(call -> {
            try (var connection = jdbc.getDataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT config_version FROM job_definition WHERE id = ?")) {
                statement.setLong(1, job.id());
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(2);
                }
            }
            return stored.remove(key(job)) != null;
        }).when(redis).delete(key(job));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jobs.update(job.id(), 1, configuration("提交后"));
            assertThat(stored).containsKey(key(job));
        });
        assertThat(stored).doesNotContainKey(key(job));
    }

    @Test
    void rollbackDoesNotEvictOrExposeUncommittedDataThroughDetailCache() {
        var job = jobs.insert(configuration("已提交"));
        details.selectById(job.id());
        clearInvocations(redis, values);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jobs.update(job.id(), 1, configuration("将回滚"));
            assertThat(details.selectById(job.id()).configuration().name()).isEqualTo("将回滚");
            status.setRollbackOnly();
        });
        verifyNoInteractions(redis, values);
        assertThat(details.selectById(job.id())).isEqualTo(job);
        assertThat(jobs.selectById(job.id())).isEqualTo(job);
    }

    @Test
    void staleVersionConflictDoesNotEvictAValidEntry() {
        var job = jobs.insert(configuration("版本冲突"));
        jobs.update(job.id(), 1, configuration("版本二"));
        details.selectById(job.id());
        clearInvocations(redis);
        assertThatThrownBy(() -> jobs.changeEnabled(job.id(), 1, true)).isInstanceOf(JobException.class);
        verifyNoInteractions(redis);
        assertThat(details.selectById(job.id()).configVersion()).isEqualTo(2);
    }

    @Test
    void redisReadAndFillFailureStillReturnsDatabaseData() {
        var job = jobs.insert(configuration("Redis 故障"));
        when(values.get(anyString())).thenThrow(new QueryTimeoutException("offline"));
        doThrow(new QueryTimeoutException("offline")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertThat(details.selectById(job.id())).isEqualTo(job);
    }

    @Test
    void exhaustedEvictionRetriesStillCommitAndCommandsIgnoreStaleCache() {
        var job = jobs.insert(configuration("初始"));
        details.selectById(job.id());
        doThrow(new QueryTimeoutException("offline")).when(redis).delete(key(job));
        clearInvocations(redis);
        var updated = jobs.update(job.id(), 1, configuration("已更新"));
        verify(redis, times(3)).delete(key(job));
        assertThat(updated.configVersion()).isEqualTo(2);
        assertThat(jobs.selectById(job.id())).isEqualTo(updated);
        // 展示缓存允许有限的旧数据；写用例继续依据数据库版本工作。
        assertThat(details.selectById(job.id()).configVersion()).isEqualTo(1);
        assertThat(jobs.changeEnabled(job.id(), 2, true).configVersion()).isEqualTo(3);
    }

    @Test
    void corruptCacheIsReplacedAndMissingJobsAreNotCached() {
        var job = jobs.insert(configuration("坏缓存"));
        stored.put(key(job), "{invalid");
        assertThat(details.selectById(job.id())).isEqualTo(job);
        assertThat(json.readTree(stored.get(key(job))).get("job").get("id").longValue()).isEqualTo(job.id());
        assertThatThrownBy(() -> details.selectById(Long.MAX_VALUE)).isInstanceOf(JobException.class);
        assertThat(stored).doesNotContainKey(properties.keyPrefix() + Long.MAX_VALUE);
    }

    @Test
    void listsAndStatisticsDoNotUseDetailCache() {
        jobs.insert(configuration("列表"));
        clearInvocations(redis, values);
        assertThat(jobs.select(new JobFilter(null, null, null, 1, 20)).total()).isEqualTo(1);
        assertThat(jobs.selectSummary().total()).isEqualTo(1);
        verifyNoInteractions(redis, values);
    }

    @Test
    void concurrentOldReadCanRefillAfterEvictionButCannotRenewItsOriginalDeadline() throws Exception {
        var job = jobs.insert(configuration("并发旧快照"));
        var snapshotRead = new CountDownLatch(1);
        var allowFill = new CountDownLatch(1);
        var firstRead = new AtomicBoolean(true);
        var snapshotTime = new AtomicReference<Instant>();
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (firstRead.compareAndSet(true, false)) {
                snapshotTime.set(Instant.now());
                snapshotRead.countDown();
                assertThat(allowFill.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(repository).findById(job.id());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var oldRead = executor.submit(() -> details.selectById(job.id()));
            try {
                assertThat(snapshotRead.await(5, TimeUnit.SECONDS)).isTrue();
                var updated = jobs.update(job.id(), 1, configuration("并发新版本"));
                assertThat(stored).doesNotContainKey(key(job));
                allowFill.countDown();
                assertThat(oldRead.get(5, TimeUnit.SECONDS)).isEqualTo(job);
                assertThat(details.selectById(job.id())).isEqualTo(job);
                assertThat(jobs.selectById(job.id())).isEqualTo(updated);
                var expiresAt = Instant.parse(json.readTree(stored.get(key(job))).get("expiresAt").stringValue());
                assertThat(expiresAt).isBeforeOrEqualTo(snapshotTime.get().plus(properties.ttl()));
                var enabled = jobs.changeEnabled(job.id(), 2, true);
                assertThat(details.selectById(job.id())).isEqualTo(enabled);
            } finally {
                allowFill.countDown();
            }
        }
    }

    private String key(JobDefinition job) {
        return properties.keyPrefix() + job.id();
    }

    private Configuration configuration(String name) {
        return new Configuration(name, null, "demoSuccess", "{\"batchSize\":100}", ScheduleType.MANUAL,
                null, "Asia/Shanghai", false, 60, 3, 10, MisfirePolicy.SKIP);
    }
}

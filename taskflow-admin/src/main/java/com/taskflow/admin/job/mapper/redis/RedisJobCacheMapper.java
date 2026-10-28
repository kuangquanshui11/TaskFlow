package com.taskflow.admin.job.mapper.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.taskflow.admin.job.config.JobDetailCacheProperties;
import com.taskflow.admin.job.event.JobDefinitionChanged;
import com.taskflow.admin.job.mapper.JobCacheMapper;
import com.taskflow.admin.job.model.JobDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

public class RedisJobCacheMapper implements JobCacheMapper {
    private static final Logger log = LoggerFactory.getLogger(RedisJobCacheMapper.class);
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final JobDetailCacheProperties properties;
    private final Clock clock;

    public RedisJobCacheMapper(StringRedisTemplate redis, JsonMapper json,
                        JobDetailCacheProperties properties, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<JobDefinition> find(long id) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            var value = redis.opsForValue().get(key(id));
            if (value == null) {
                return Optional.empty();
            }
            var entry = json.readValue(value, Entry.class);
            if (entry == null || entry.expiresAt() == null || !entry.expiresAt().isAfter(clock.instant())
                    || entry.job() == null || entry.job().id() != id || entry.job().configVersion() < 1
                    || entry.job().configuration() == null || entry.job().createdAt() == null
                    || entry.job().updatedAt() == null) {
                return Optional.empty();
            }
            // 缓存内容损坏同样视为未命中，避免将非法 payload 传给 HTTP 响应转换。
            var payload = entry.job().configuration().payloadJson();
            if (payload != null && !json.readTree(payload).isObject()) {
                return Optional.empty();
            }
            return Optional.of(entry.job());
        } catch (RuntimeException failure) {
            log.warn("Job detail cache read failed; falling back to database. jobId={}, failureType={}",
                    id, failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void store(JobDefinition job, Instant readStartedAt) {
        if (!properties.enabled()) {
            return;
        }
        try {
            var expiresAt = readStartedAt.plus(properties.ttl());
            var value = json.writeValueAsString(new Entry(expiresAt, job));
            var remaining = Duration.between(clock.instant(), expiresAt);
            if (remaining.toMillis() > 0) {
                // 内容中的绝对过期时间还会在读取时校验，覆盖网络/Redis 排队造成的 TTL 延迟。
                redis.opsForValue().set(key(job.id()), value, remaining);
            }
        } catch (RuntimeException failure) {
            log.warn("Job detail cache fill failed; returning database result. jobId={}, failureType={}",
                    job.id(), failure.getClass().getSimpleName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidateAfterCommit(JobDefinitionChanged event) {
        if (!properties.enabled()) {
            return;
        }
        for (int attempt = 1; attempt <= properties.evictionAttempts(); attempt++) {
            try {
                redis.delete(key(event.jobId()));
                return;
            } catch (RuntimeException failure) {
                log.warn("Job detail cache eviction failed. jobId={}, attempt={}/{}, failureType={}",
                        event.jobId(), attempt, properties.evictionAttempts(), failure.getClass().getSimpleName());
            }
        }
        // 数据库已经提交，不能将成功写入伪装成失败；耗尽重试后由有限 TTL 淘汰旧数据。
        log.error("Job detail cache eviction exhausted; waiting for TTL expiry. jobId={}", event.jobId());
    }

    private String key(long id) {
        return properties.keyPrefix() + id;
    }

    public record Entry(Instant expiresAt, JobDefinition job) {
    }
}

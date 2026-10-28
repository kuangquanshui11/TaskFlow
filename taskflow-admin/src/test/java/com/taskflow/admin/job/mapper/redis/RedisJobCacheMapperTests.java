package com.taskflow.admin.job.mapper.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.taskflow.admin.job.config.JobDetailCacheProperties;
import com.taskflow.admin.job.event.JobDefinitionChanged;
import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import com.taskflow.admin.job.model.JobDefinition.MisfirePolicy;
import com.taskflow.admin.job.model.JobDefinition.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisJobCacheMapperTests {
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    private static final String PREFIX = "taskflow:admin:test:detail:v1:";
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final JsonMapper json = new JsonMapper();
    private final JobDetailCacheProperties properties = new JobDetailCacheProperties(true, Duration.ofSeconds(60), PREFIX, 3);
    private RedisJobCacheMapper cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        cache = new RedisJobCacheMapper(redis, json, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void roundTripPreservesNullsNestedJsonEnumsAndUtcTimes() {
        var job = job();
        cache.store(job, NOW);
        var encoded = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(PREFIX + 7), encoded.capture(), eq(Duration.ofSeconds(60)));
        when(values.get(PREFIX + 7)).thenReturn(encoded.getValue());
        assertThat(cache.find(7)).contains(job);
    }

    @Test
    void delayedFillOnlyUsesTheRemainingTtlAndRetainsTheAbsoluteDeadline() {
        cache.store(job(), NOW.minusSeconds(45));
        var encoded = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(PREFIX + 7), encoded.capture(), eq(Duration.ofSeconds(15)));
        var entry = json.readValue(encoded.getValue(), RedisJobCacheMapper.Entry.class);
        assertThat(entry.expiresAt()).isEqualTo(NOW.plusSeconds(15));
    }

    @Test
    void readTakingLongerThanTtlIsNotCached() {
        cache.store(job(), NOW.minusSeconds(60));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void expiredEntryIsRejectedEvenIfRedisStillReturnsIt() {
        when(values.get(PREFIX + 7)).thenReturn(json.writeValueAsString(new RedisJobCacheMapper.Entry(NOW, job())));
        assertThat(cache.find(7)).isEmpty();
    }

    @Test
    void invalidJsonMissingFieldsAndWrongJobCannotBecomeCacheHits() {
        for (String invalid : new String[]{"{", "null", "{}",
                json.writeValueAsString(new RedisJobCacheMapper.Entry(NOW.plusSeconds(60), job()))}) {
            when(values.get(PREFIX + 8)).thenReturn(invalid);
            assertThat(cache.find(8)).as(invalid).isEmpty();
        }
    }

    @Test
    void unavailableRedisDoesNotPropagateReadOrFillFailures() {
        when(values.get(anyString())).thenThrow(new QueryTimeoutException("not exposed"));
        doThrow(new QueryTimeoutException("not exposed")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertThat(cache.find(7)).isEmpty();
        assertThatCode(() -> cache.store(job(), NOW)).doesNotThrowAnyException();
        verify(values, times(1)).get(anyString());
        verify(values, times(1)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void deletionStopsOnFirstSuccessIncludingAnAlreadyAbsentKey() {
        when(redis.delete(PREFIX + 7)).thenThrow(new QueryTimeoutException("temporary")).thenReturn(false);
        cache.invalidateAfterCommit(new JobDefinitionChanged(7));
        verify(redis, times(2)).delete(PREFIX + 7);
    }

    @Test
    void exhaustedDeletionIsBoundedAndDoesNotMaskCommittedWrites() {
        when(redis.delete(anyString())).thenThrow(new QueryTimeoutException("offline"));
        assertThatCode(() -> cache.invalidateAfterCommit(new JobDefinitionChanged(7))).doesNotThrowAnyException();
        verify(redis, times(3)).delete(PREFIX + 7);
    }

    @Test
    void disabledCachePerformsNoRedisOperations() {
        clearInvocations(redis);
        var disabled = new RedisJobCacheMapper(redis, json,
                new JobDetailCacheProperties(false, properties.ttl(), PREFIX, 3), Clock.systemUTC());
        assertThat(disabled.find(7)).isEmpty();
        disabled.store(job(), NOW);
        disabled.invalidateAfterCommit(new JobDefinitionChanged(7));
        verifyNoInteractions(redis, values);
    }

    @Test
    void invalidTtlNamespaceOrUnboundedRetriesFailConfiguration() {
        assertThatThrownBy(() -> new JobDetailCacheProperties(true, Duration.ZERO, PREFIX, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobDetailCacheProperties(true, Duration.ofSeconds(60), "job:", 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobDetailCacheProperties(true, Duration.ofSeconds(60), PREFIX, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JobDefinition job() {
        return new JobDefinition(7, new Configuration("中文任务", null, "demoSuccess",
                "{\"nested\":{\"value\":\"换行\\n引号\\\"\"},\"items\":[1,true,null]}",
                ScheduleType.MANUAL, null, "Asia/Shanghai", false, 60, 3, 10, MisfirePolicy.SKIP), 2, NOW, NOW);
    }
}

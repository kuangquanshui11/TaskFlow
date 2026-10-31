package com.taskflow.worker.observability;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import com.taskflow.worker.observability.service.IMetricsSampler;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {"taskflow.metrics.enabled=true", "taskflow.metrics.sample-interval=5m"})
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.taskflow.worker.support.IsolatedTestDatabaseInitializer.class)
class WorkerMetricsTests {
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    @Autowired IMetricsSampler sampler;
    @Autowired MeterRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.core.env.Environment environment;
    @MockitoSpyBean ExecutionMapper mapper;
    @MockitoSpyBean(name = "metricsClock") Clock clock;

    @BeforeEach void clean() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:taskflow-worker-");
        }
        jdbc.update("DELETE FROM worker_execution");
        doReturn(NOW).when(clock).instant(); doReturn(NOW.toEpochMilli()).when(clock).millis();
    }

    @Test void bootLoadsOtlpMetricsDefaultsAndExporterBinding() {
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("management.otlp.metrics.export.step")).isEqualTo("60s");
        String traces = environment.getProperty("TASKFLOW_OTLP_ENDPOINT", "http://localhost:4318/v1/traces");
        assertThat(environment.getProperty("management.otlp.metrics.export.url")).isEqualTo(traces.replace("/v1/traces", "/v1/metrics"));
        var properties = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("management.otlp.metrics.export", org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsProperties.class).get();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getStep()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(properties.getUrl()).endsWith("/v1/metrics");
    }

    @Test void emptyDatabaseAndStableRegistration() {
        double before = counter("ok");
        sampler.sample();
        assertThat(counter("ok")).isEqualTo(before + 1);
        assertThat(gauge("taskflow.metrics.sample.age")).isZero();
        assertThat(gauge("taskflow.worker.result.unreported")).isZero();
        assertThat(gauge("taskflow.worker.execution.backlog")).isZero();
        int meters = registry.getMeters().size();
        sampler.sample(); sampler.sample();
        assertThat(registry.getMeters()).hasSize(meters);
    }

    @Test void failureRetainsWholeSnapshotAndAgeAdvancesWithoutDatabaseCallbacks() {
        execution("SUCCEEDED", false);
        sampler.sample();
        var previous = new java.util.HashMap<io.micrometer.core.instrument.Meter.Id, Double>();
        registry.getMeters().stream().filter(m -> m.getId().getName().startsWith("taskflow.")
                && !m.getId().getName().equals("taskflow.metrics.sample.age") && m instanceof io.micrometer.core.instrument.Gauge)
                .forEach(m -> previous.put(m.getId(), ((io.micrometer.core.instrument.Gauge)m).value()));
        double failures = counter("failed"); double successes = counter("ok");
        jdbc.update("DELETE FROM worker_execution");
        doThrow(new DataAccessResourceFailureException("injected")).when(mapper).countAccepted();
        doReturn(NOW.plusSeconds(30)).when(clock).instant();
        doReturn(NOW.plusSeconds(30).toEpochMilli()).when(clock).millis();
        sampler.sample();
        assertThat(counter("failed")).isEqualTo(failures + 1);
        assertThat(counter("ok")).isEqualTo(successes);
        assertThat(gauge("taskflow.metrics.sample.age")).isEqualTo(30);
        clearInvocations(mapper);
        registry.getMeters().stream().filter(m -> previous.containsKey(m.getId())).forEach(m ->
                assertThat(((io.micrometer.core.instrument.Gauge)m).value()).isEqualTo(previous.get(m.getId())));
        verifyNoInteractions(mapper);
    }

    @Test void injectedResultsAndExecutionQueue() {
        execution("SUCCEEDED", false); execution("FAILED", false);
        execution("SUCCEEDED", true); execution("FAILED", true);
        for(int i=0;i<3;i++) execution("ACCEPTED", false);
        execution("RUNNING", false);
        sampler.sample();
        assertThat(gauge("taskflow.worker.result.unreported")).isEqualTo(2);
        assertThat(gauge("taskflow.worker.execution.backlog")).isEqualTo(3);
    }
    private void execution(String state, boolean reported) {
        jdbc.update("INSERT INTO worker_execution(attempt_id,run_id,fingerprint,dispatch_json,state,retryable,reported,report_count,next_report_at,created_at) VALUES(?,?,'fingerprint','{}',?,FALSE,?,99,?,?)",UUID.randomUUID().toString(),UUID.randomUUID().toString(),state,reported,Timestamp.from(NOW.plusSeconds(3600)),Timestamp.from(NOW));
    }
    private double gauge(String name) { return registry.get(name).gauge().value(); }
    private double counter(String outcome) { return registry.get("taskflow.metrics.sample").tag("outcome",outcome).counter().count(); }
}

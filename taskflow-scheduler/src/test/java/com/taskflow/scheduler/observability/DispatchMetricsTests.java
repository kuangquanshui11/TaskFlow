package com.taskflow.scheduler.observability;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import com.taskflow.scheduler.observability.service.IMetricsSampler;
import com.taskflow.scheduler.node.mapper.WorkerNodeMapper;
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
@org.springframework.test.context.ContextConfiguration(initializers = com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class DispatchMetricsTests {
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    @Autowired IMetricsSampler sampler;
    @Autowired MeterRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.core.env.Environment environment;
    @MockitoSpyBean WorkerNodeMapper mapper;
    @MockitoSpyBean(name = "metricsClock") Clock clock;

    @BeforeEach void clean() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:taskflow-scheduler-");
        }
        jdbc.update("DELETE FROM dispatch_outbox"); jdbc.update("DELETE FROM run_attempt");
        jdbc.update("DELETE FROM run_execution"); jdbc.update("DELETE FROM worker_node_session"); jdbc.update("DELETE FROM worker_node");
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
        for (String state : new String[]{"WAITING","READY","SENDING","PUBLISHED","FAILED","CANCELLED"})
            assertThat(registry.get("taskflow.dispatch.outbox.backlog").tag("state",state).gauge().value()).isZero();
        for (String name : new String[]{"outbox.publish.overdue","outbox.claim.expired","attempt.dispatch.overdue","attempt.lease.expired","attempt.lease.expiring"})
            assertThat(gauge("taskflow.dispatch." + name)).isZero();
        assertThat(gauge("taskflow.worker.node.heartbeat.age")).isEqualTo(-1);
        int meters = registry.getMeters().size();
        sampler.sample(); sampler.sample();
        assertThat(registry.getMeters()).hasSize(meters);
    }

    @Test void failureRetainsWholeSnapshotAndAgeAdvancesWithoutDatabaseCallbacks() {
        outbox("WAITING", NOW.minusSeconds(5));
        sampler.sample();
        var previous = new java.util.HashMap<io.micrometer.core.instrument.Meter.Id, Double>();
        registry.getMeters().stream().filter(m -> m.getId().getName().startsWith("taskflow.")
                && !m.getId().getName().equals("taskflow.metrics.sample.age") && m instanceof io.micrometer.core.instrument.Gauge)
                .forEach(m -> previous.put(m.getId(), ((io.micrometer.core.instrument.Gauge)m).value()));
        double failures = counter("failed"); double successes = counter("ok");
        jdbc.update("DELETE FROM dispatch_outbox");
        doThrow(new DataAccessResourceFailureException("injected")).when(mapper).oldestOnlineHeartbeat(any());
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

    @Test void injectedOutboxBacklog() {
        for (int i=0;i<7;i++) outbox("WAITING", NOW.minusSeconds(5));
        for (int i=0;i<2;i++) outbox("READY", NOW.minusSeconds(5));
        outbox("SENDING", NOW.minusSeconds(5));
        outbox("READY", NOW.plusSeconds(5));
        for (String state : new String[]{"PUBLISHED","FAILED","CANCELLED"}) outbox(state, NOW.minusSeconds(5));
        sampler.sample();
        assertThat(registry.get("taskflow.dispatch.outbox.backlog").tag("state","WAITING").gauge().value()).isEqualTo(7);
        assertThat(registry.get("taskflow.dispatch.outbox.backlog").tag("state","READY").gauge().value()).isEqualTo(3);
        assertThat(gauge("taskflow.dispatch.outbox.publish.overdue")).isEqualTo(2);
        assertThat(gauge("taskflow.dispatch.outbox.claim.expired")).isEqualTo(1);
    }

    @Test void injectedAttemptsRespectStatesAndTimeBoundaries() {
        attempt("READY", NOW, null);
        attempt("DISPATCHING", NOW, null);
        attempt("RUNNING", NOW, NOW);
        attempt("RUNNING", NOW, NOW.plusSeconds(60));
        attempt("RUNNING", NOW, NOW.plusSeconds(61));
        for (String state : new String[]{"SUCCEEDED","FAILED","TIMED_OUT"}) {
            attempt(state, NOW.minusSeconds(5), NOW.minusSeconds(5));
            attempt(state, NOW, NOW.plusSeconds(30));
        }
        sampler.sample();
        assertThat(gauge("taskflow.dispatch.attempt.dispatch.overdue")).isEqualTo(2);
        assertThat(gauge("taskflow.dispatch.attempt.lease.expired")).isEqualTo(1);
        assertThat(gauge("taskflow.dispatch.attempt.lease.expiring")).isEqualTo(1);
    }

    @Test void onlineHeartbeatUsesOldestTimestampAndIgnoresOfflineNodes() {
        node("fresh", NOW, NOW.plusSeconds(30));
        node("old", NOW.minusSeconds(120), NOW.plusSeconds(30));
        node("offline", NOW.minusSeconds(600), NOW);
        sampler.sample();
        assertThat(gauge("taskflow.worker.node.heartbeat.age")).isBetween(115.0,130.0);
    }

    @Test void samplingUsesIndependentStatementsOutsideTransactions() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(mapper).oldestOnlineHeartbeat(any());
        sampler.sample();
        verify(mapper).oldestOnlineHeartbeat(NOW);
    }

    private void outbox(String state, Instant time) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO dispatch_outbox(event_id,attempt_id,state,publish_attempts,next_publish_at,claim_version,claim_until,created_at) VALUES(?,?,?,0,?,0,?,?)",id,id,state,Timestamp.from(time),Timestamp.from(time),Timestamp.from(NOW));
    }
    private void attempt(String state, Instant dispatch, Instant expiry) {
        jdbc.update("INSERT INTO run_attempt(id,run_id,attempt_number,lease_version,state,dispatch_count,next_dispatch_at,expires_at) VALUES(?,?,1,1,?,0,?,?)",UUID.randomUUID().toString(),UUID.randomUUID().toString(),state,Timestamp.from(dispatch),expiry==null?null:Timestamp.from(expiry));
    }
    private void node(String id, Instant heartbeat, Instant expiry) {
        jdbc.update("INSERT INTO worker_node(worker_id,display_name,session_id,generation,registration_fingerprint,handler_keys_json,parallelism,max_pending,running_count,pending_count,dispatch_transport,last_sequence,registered_at,last_heartbeat_at,expires_at) VALUES(?,?,?,1,'fingerprint','[]',1,4,0,0,'http',0,?,?,?)",id,id,UUID.randomUUID().toString(),Timestamp.from(NOW),Timestamp.from(heartbeat),Timestamp.from(expiry));
    }
    private double gauge(String name) { return registry.get(name).gauge().value(); }
    private double counter(String outcome) { return registry.get("taskflow.metrics.sample").tag("outcome",outcome).counter().count(); }
}

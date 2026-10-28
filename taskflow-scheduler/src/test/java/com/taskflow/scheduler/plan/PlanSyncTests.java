package com.taskflow.scheduler.plan;

import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.taskflow.scheduler.plan.dto.PlanSyncRequest;
import com.taskflow.scheduler.plan.mapper.PlanMapper;
import com.taskflow.scheduler.plan.service.IPlanService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.taskflow.scheduler.plan.model.PlanRecords.CalculationState;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class PlanSyncTests {
    @Autowired IPlanService plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean PlanMapper mapper;
    @MockitoBean(name = "planClock") Clock clock;
    private static final Instant BASE = Instant.parse("2026-10-26T00:00:00Z");
    @Value("${local.server.port}") int port;
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM schedule_sync_inbox"); jdbc.update("DELETE FROM schedule_plan");
        when(clock.instant()).thenReturn(BASE);
    }

    @Test void httpProtocolAuthenticationValidationAndDuplicateAcknowledgement() throws Exception {
        var input = event(1, false, true);
        assertThat(http(input, null).statusCode()).isEqualTo(401);
        assertThat(http(input, "wrong").statusCode()).isEqualTo(401);
        var applied = http(input, "test-control-token");
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
        assertThat(json.readTree(applied.body()).get("acceptedVersion").longValue()).isEqualTo(1);
        assertThat(json.readTree(http(input,"test-control-token").body()).get("result").stringValue()).isEqualTo("DUPLICATE");
        assertThat(http(new PlanSyncRequest(UUID.randomUUID(), 7, 1, true, input.configuration()),"test-control-token").statusCode()).isEqualTo(409);
        assertThat(http(new PlanSyncRequest(input.eventId(), 8, 1, false, input.configuration()),"test-control-token").statusCode()).isEqualTo(409);
        assertThat(http(new PlanSyncRequest(UUID.randomUUID(), 7, 0, false, input.configuration()),"test-control-token").statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_sync_inbox",Long.class)).isEqualTo(1);
    }

    @Test void deletionTombstoneAndNewerVersionPreventLateReenable() {
        plans.update(event(3, true, true));
        var old = event(1, false, true);
        assertThat(plans.update(old).result()).isEqualTo("SUPERSEDED");
        assertThat(plans.update(old).acceptedVersion()).isEqualTo(3);
        assertThat(plans.selectByJobId(7).deleted()).isTrue();
        assertThat(plans.selectByJobId(7).enabled()).isFalse();
        assertThat(plans.selectByJobId(7).configVersion()).isEqualTo(3);
    }

    @Test void concurrentDuplicatesCreateOneReceiptAndConcurrentVersionsRetainHighest() throws Exception {
        var input = event(1, false, false);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> plans.update(input));
            var b = executor.submit(() -> plans.update(input));
            assertThat(a.get(5, TimeUnit.SECONDS).acceptedVersion()).isEqualTo(1);
            assertThat(b.get(5, TimeUnit.SECONDS).acceptedVersion()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_sync_inbox",Long.class)).isEqualTo(1);
            var changed = event(2, false, true);
            var deleted = event(3, true, false);
            var c = executor.submit(() -> plans.update(changed));
            var d = executor.submit(() -> plans.update(deleted));
            c.get(5, TimeUnit.SECONDS); d.get(5, TimeUnit.SECONDS);
        }
        assertThat(plans.selectByJobId(7).configVersion()).isEqualTo(3);
        assertThat(plans.selectByJobId(7).deleted()).isTrue();
    }

    @Test void receiptAndPlanAreAtomicAndCanBeRetriedAfterRollback() {
        var input = event(1, false, true);
        doThrow(new IllegalStateException("injected")).when(mapper).insert(any());
        assertThatThrownBy(() -> plans.update(input)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_sync_inbox",Long.class)).isZero();
        reset(mapper);
        assertThat(plans.update(input).result()).isEqualTo("APPLIED");
    }

    @Test void rejectsInvalidCronZoneAndPayload() throws Exception {
        var config = event(1, false, true).configuration();
        var tree = json.valueToTree(config);
        for (var change : Map.of("cronExpression","bad", "timeZone","Invalid/Zone", "payloadJson","[]").entrySet()) {
            var object = (tools.jackson.databind.node.ObjectNode) tree.deepCopy();
            object.put(change.getKey(), change.getValue());
            var invalid = new PlanSyncRequest(UUID.randomUUID(), 7, 1, false, json.treeToValue(object, PlanSyncRequest.Configuration.class));
            assertThat(http(invalid,"test-control-token").statusCode()).as(change.getKey()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_plan",Long.class)).isZero();
    }

    @Test void persistsCalculatedTimeAndQueryDoesNotAdvanceIt() throws Exception {
        plans.update(daily(1, true, "Asia/Shanghai"));
        var plan = plans.selectByJobId(7);
        assertThat(plan.nextFireTime()).isEqualTo(Instant.parse("2026-10-26T01:00:00Z"));
        assertThat(plan.calculatedAt()).isEqualTo(BASE);
        assertThat(plan.calculationState()).isEqualTo(CalculationState.READY);
        assertThat(jdbc.queryForObject("SELECT next_fire_time FROM schedule_plan WHERE job_id=7", java.sql.Timestamp.class).toInstant())
                .isEqualTo(plan.nextFireTime());
        when(clock.instant()).thenReturn(BASE.plusSeconds(86400));
        assertThat(plans.selectByJobId(7)).isEqualTo(plan);
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/plans/selectByJobId?id=7"))
                    .header("X-Taskflow-Token", "test-control-token").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("nextFireTime").stringValue()).isEqualTo("2026-10-26T01:00:00Z");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_execution", Long.class)).isZero();
    }

    @Test void zoneCronAndEnableChangesRecalculateButMetadataAndReplaysPreserveTime() {
        var original = daily(1, true, "Asia/Shanghai");
        plans.update(original);
        var first = plans.selectByJobId(7);
        when(clock.instant()).thenReturn(BASE.plusSeconds(7200));
        plans.update(original);
        assertThat(plans.selectByJobId(7)).isEqualTo(first);
        var config = original.configuration();
        var renamed = new PlanSyncRequest.Configuration("renamed", "new description", "demoFail", "{\"x\":2}",
                config.scheduleType(), config.cronExpression(), config.timeZone(), true, 60, 2, 10, "FIRE_ONCE");
        plans.update(new PlanSyncRequest(UUID.randomUUID(), 7, 2, false, renamed));
        assertThat(plans.selectByJobId(7).nextFireTime()).isEqualTo(first.nextFireTime());
        assertThat(plans.selectByJobId(7).calculatedAt()).isEqualTo(BASE);
        plans.update(daily(3, true, "UTC"));
        assertThat(plans.selectByJobId(7).nextFireTime()).isEqualTo(Instant.parse("2026-10-26T09:00:00Z"));
        var changedCron = new PlanSyncRequest.Configuration("job",null,"demoSuccess",null,"CRON","0 0 3 * * *","UTC",true,30,3,5,"SKIP");
        plans.update(new PlanSyncRequest(UUID.randomUUID(),7,4,false,changedCron));
        assertThat(plans.selectByJobId(7).nextFireTime()).isEqualTo(Instant.parse("2026-10-26T03:00:00Z"));
        var latest = plans.selectByJobId(7);
        plans.update(original);
        assertThat(plans.selectByJobId(7)).isEqualTo(latest);
        plans.update(daily(5,false,"UTC"));
        assertThat(plans.selectByJobId(7).nextFireTime()).isNull();
        assertThat(plans.selectByJobId(7).calculationState()).isEqualTo(CalculationState.DISABLED);
        when(clock.instant()).thenReturn(BASE.plusSeconds(36000));
        plans.update(daily(6,true,"UTC"));
        assertThat(plans.selectByJobId(7).nextFireTime()).isEqualTo(Instant.parse("2026-10-27T09:00:00Z"));
    }

    @Test void manualAndDeletedPlansClearTimeAndImpossibleCronHasExplicitState() {
        plans.update(daily(1,true,"UTC"));
        var manual = new PlanSyncRequest.Configuration("manual",null,"demoSuccess",null,"MANUAL",null,"UTC",true,30,3,5,"SKIP");
        plans.update(new PlanSyncRequest(UUID.randomUUID(),7,2,false,manual));
        assertThat(plans.selectByJobId(7).nextFireTime()).isNull();
        assertThat(plans.selectByJobId(7).calculationState()).isEqualTo(CalculationState.MANUAL);
        var impossible = new PlanSyncRequest.Configuration("impossible",null,"demoSuccess",null,"CRON","0 0 0 31 2 *","UTC",true,30,3,5,"SKIP");
        plans.update(new PlanSyncRequest(UUID.randomUUID(),7,3,false,impossible));
        assertThat(plans.selectByJobId(7).calculationState()).isEqualTo(CalculationState.NO_FUTURE_FIRE);
        assertThat(plans.selectByJobId(7).nextFireTime()).isNull();
        plans.update(new PlanSyncRequest(UUID.randomUUID(),7,4,true,manual));
        assertThat(plans.selectByJobId(7).calculationState()).isEqualTo(CalculationState.DELETED);
        assertThat(plans.selectByJobId(7).enabled()).isFalse();
    }

    @Test void pendingUpgradeCalculationIsIdempotentConcurrentAndNeverOverwritesNewConfiguration() throws Exception {
        plans.update(daily(1,true,"UTC"));
        jdbc.update("UPDATE schedule_plan SET calculation_state='PENDING',next_fire_time=NULL,calculated_at=NULL");
        when(clock.instant()).thenReturn(BASE.plusSeconds(36000));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(plans::initializePending);
            var b = executor.submit(plans::initializePending);
            a.get(5,TimeUnit.SECONDS); b.get(5,TimeUnit.SECONDS);
        }
        assertThat(plans.selectByJobId(7).nextFireTime()).isEqualTo(Instant.parse("2026-10-27T09:00:00Z"));
        var retained = plans.selectByJobId(7);
        when(clock.instant()).thenReturn(BASE.plusSeconds(90000));
        plans.initializePending();
        assertThat(plans.selectByJobId(7)).isEqualTo(retained);
        jdbc.update("UPDATE schedule_plan SET calculation_state='PENDING',next_fire_time=NULL,calculated_at=NULL");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(plans::initializePending);
            var b = executor.submit(() -> plans.update(daily(2,false,"UTC")));
            a.get(5,TimeUnit.SECONDS); b.get(5,TimeUnit.SECONDS);
        }
        assertThat(plans.selectByJobId(7).configVersion()).isEqualTo(2);
        assertThat(plans.selectByJobId(7).calculationState()).isEqualTo(CalculationState.DISABLED);
        assertThat(plans.selectByJobId(7).nextFireTime()).isNull();
    }

    @Test void invalidLegacySnapshotDoesNotPreventOtherPlansFromBeingInitialized() {
        plans.update(daily(1,true,"UTC"));
        jdbc.update("UPDATE schedule_plan SET calculation_state='PENDING',snapshot_json='broken'");
        var good = daily(1,true,"UTC");
        plans.update(new PlanSyncRequest(UUID.randomUUID(),6,1,false,good.configuration()));
        jdbc.update("UPDATE schedule_plan SET calculation_state='PENDING',snapshot_json='null' WHERE job_id=6");
        plans.update(new PlanSyncRequest(UUID.randomUUID(),8,1,false,good.configuration()));
        jdbc.update("UPDATE schedule_plan SET calculation_state='PENDING' WHERE job_id=8");
        plans.initializePending();
        assertThat(plans.selectByJobId(6).calculationState()).isEqualTo(CalculationState.INVALID);
        assertThat(plans.selectByJobId(7).calculationState()).isEqualTo(CalculationState.INVALID);
        assertThat(plans.selectByJobId(8).calculationState()).isEqualTo(CalculationState.READY);
    }

    @Test void failedPlanUpdateRollsBackReceiptTimeAndVersionTogether() {
        plans.update(daily(1,true,"UTC"));
        var original = plans.selectByJobId(7);
        doThrow(new IllegalStateException("injected")).when(mapper).update(any());
        assertThatThrownBy(() -> plans.update(daily(2,false,"UTC"))).isInstanceOf(IllegalStateException.class);
        assertThat(plans.selectByJobId(7)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_sync_inbox",Long.class)).isEqualTo(1);
    }

    private PlanSyncRequest daily(long version, boolean enabled, String zone) {
        return new PlanSyncRequest(UUID.randomUUID(),7,version,false,new PlanSyncRequest.Configuration(
                "daily",null,"demoSuccess",null,"CRON","0 0 9 * * *",zone,enabled,30,3,5,"SKIP"));
    }

    private PlanSyncRequest event(long version, boolean deleted, boolean enabled) {
        return new PlanSyncRequest(UUID.randomUUID(),7,version,deleted,new PlanSyncRequest.Configuration(
                "job",null,"demoSuccess","{\"n\":1}","CRON","0 */5 * * * *","Asia/Shanghai",enabled,30,3,5,"SKIP"));
    }
    private HttpResponse<String> http(Object input, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/plans/update"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
        if (token != null) builder.header("X-Taskflow-Token",token);
        try (var client = HttpClient.newHttpClient()) { return client.send(builder.build(),HttpResponse.BodyHandlers.ofString()); }
    }
}

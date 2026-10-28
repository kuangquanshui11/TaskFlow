package com.taskflow.scheduler.plan;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.*;
import com.taskflow.scheduler.execution.client.WorkerClient;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.model.RunRecords.RunRow;
import com.taskflow.scheduler.execution.service.IRunService;
import com.taskflow.scheduler.plan.dto.PlanSyncRequest;
import com.taskflow.scheduler.plan.mapper.PlanMapper;
import com.taskflow.scheduler.plan.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles(resolver = PlanMigrationTests.DatabaseProfiles.class)
@ContextConfiguration(initializers = PlanMigrationTests.Guard.class)
class PlanTriggerTests {
    private static final Instant BASE = Instant.parse("2026-10-26T00:00:00Z");
    @Autowired IPlanService plans;
    @Autowired IPlanTriggerService triggers;
    @Autowired IRunService runs;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name="planClock") Clock clock;
    @MockitoBean WorkerClient worker;
    @MockitoSpyBean RunDataMapper runMapper;
    @MockitoSpyBean PlanMapper planMapper;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM run_attempt"); jdbc.update("DELETE FROM run_execution");
        jdbc.update("DELETE FROM schedule_sync_inbox"); jdbc.update("DELETE FROM schedule_plan");
        when(clock.instant()).thenReturn(BASE);
    }

    @Test void dueScanCreatesOneRunAndAttemptAdvancesTimeThenReusesDispatchOutsideTransaction() {
        plans.update(event(7,1,true,"SKIP"));
        triggers.scanDue();
        assertThat(count("run_execution")).isZero();
        var due = plans.selectByJobId(7).nextFireTime();
        when(clock.instant()).thenReturn(due.plusMillis(200));
        triggers.scanDue(); triggers.scanDue();
        assertThat(count("run_execution")).isEqualTo(1);
        assertThat(count("run_attempt")).isEqualTo(1);
        var run = runs.select(7,1,10).records().getFirst();
        assertThat(run.triggerType()).isEqualTo("CRON");
        assertThat(run.scheduledAt()).isEqualTo(due);
        assertThat(plans.selectByJobId(7).nextFireTime()).isEqualTo(BASE.plusSeconds(120));
        verifyNoInteractions(worker);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Dispatch dispatch = call.getArgument(0);
            runs.updateResult(new Result(dispatch.attemptId(),dispatch.runId(),dispatch.leaseVersion(),"SUCCEEDED","SUCCEEDED","ok",false));
            return null;
        }).when(worker).dispatch(any());
        runs.dispatchPending();
        assertThat(runs.selectById(run.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(runs.selectById(run.id()).scheduledAt()).isEqualTo(due);
    }

    @Test void concurrentScannersAndDatabaseUniqueKeyAllowOnlyOneLogicalScheduledRun() throws Exception {
        plans.update(event(7,1,true,"FIRE_ONCE"));
        var due = plans.selectByJobId(7).nextFireTime();
        when(clock.instant()).thenReturn(due);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var first = executor.submit(() -> { gate.await(); triggers.scanDue(); return true; });
            var second = executor.submit(() -> { gate.await(); triggers.scanDue(); return true; });
            gate.countDown(); first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS);
        }
        assertThat(count("run_execution")).isEqualTo(1);
        assertThat(count("run_attempt")).isEqualTo(1);
        var row = runMapper.bySchedule(7,due).orElseThrow();
        var duplicate = new RunRow(UUID.randomUUID(),7,99,UUID.randomUUID(),row.fingerprint(),"READY",row.snapshotJson(),1,
                null,null,row.createdAt(),row.updatedAt(),"CRON",due);
        assertThatThrownBy(() -> runMapper.insert(duplicate)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        jdbc.update("UPDATE schedule_plan SET next_fire_time=? WHERE job_id=7",Timestamp.from(due));
        triggers.scanDue();
        assertThat(count("run_execution")).isEqualTo(1);
        assertThat(count("run_attempt")).isEqualTo(1);
        assertThat(plans.selectByJobId(7).nextFireTime()).isAfter(due);
    }

    @Test void firstAttemptFailureRollsBackRunAndPlanThenRecoversOnNextScan() {
        plans.update(event(7,1,true,"SKIP"));
        var before = plans.selectByJobId(7);
        when(clock.instant()).thenReturn(before.nextFireTime());
        doThrow(new IllegalStateException("injected")).when(runMapper).insertAttempt(any());
        triggers.scanDue();
        assertThat(count("run_execution")).isZero();
        assertThat(count("run_attempt")).isZero();
        assertThat(plans.selectByJobId(7)).isEqualTo(before);
        reset(runMapper);
        triggers.scanDue();
        assertThat(count("run_execution")).isEqualTo(1);
        assertThat(count("run_attempt")).isEqualTo(1);
    }

    @Test void planAdvanceFailureRollsBackAlreadyInsertedRunAndDoesNotBlockOtherJobs() {
        plans.update(event(7,1,true,"SKIP")); plans.update(event(8,1,true,"SKIP"));
        var before = plans.selectByJobId(7);
        when(clock.instant()).thenReturn(before.nextFireTime());
        doThrow(new IllegalStateException("injected")).when(planMapper).advance(argThat(plan -> plan.jobId()==7),any());
        triggers.scanDue();
        assertThat(runs.select(7,1,10).total()).isZero();
        assertThat(runs.select(8,1,10).total()).isEqualTo(1);
        assertThat(plans.selectByJobId(7)).isEqualTo(before);
        reset(planMapper);
        triggers.scanDue();
        assertThat(runs.select(7,1,10).total()).isEqualTo(1);
    }

    @Test void misfireSkipCreatesNoRunAndFireOnceCoalescesManyMissedSlotsIntoOneRun() {
        plans.update(event(7,1,true,"SKIP")); plans.update(event(8,1,true,"FIRE_ONCE"));
        var oldest = plans.selectByJobId(8).nextFireTime();
        var recoveredAt = BASE.plusSeconds(3610);
        when(clock.instant()).thenReturn(recoveredAt);
        triggers.scanDue(); triggers.scanDue();
        assertThat(runs.select(7,1,10).total()).isZero();
        assertThat(runs.select(8,1,10).total()).isEqualTo(1);
        assertThat(runs.select(8,1,10).records().getFirst().scheduledAt()).isEqualTo(oldest);
        assertThat(plans.selectByJobId(7).nextFireTime()).isAfter(recoveredAt);
        assertThat(plans.selectByJobId(8).nextFireTime()).isAfter(recoveredAt);
    }

    @Test void graceBoundaryTriggersAndCorruptedLegacySnapshotDoesNotStopHealthyPlan() {
        plans.update(event(7,1,true,"SKIP")); plans.update(event(8,1,true,"SKIP"));
        var due = plans.selectByJobId(8).nextFireTime();
        jdbc.update("UPDATE schedule_plan SET snapshot_json='null' WHERE job_id=7");
        when(clock.instant()).thenReturn(due.plusSeconds(5));
        triggers.scanDue();
        assertThat(plans.selectByJobId(7).calculationState().name()).isEqualTo("INVALID");
        assertThat(runs.select(8,1,10).total()).isEqualTo(1);
    }

    @Test void snapshotIsFrozenAndBusinessRetryReusesRunWhileManualInvocationsStayIndependent() {
        plans.update(event(7,1,true,"FIRE_ONCE"));
        var due = plans.selectByJobId(7).nextFireTime();
        when(clock.instant()).thenReturn(due);
        triggers.scanDue();
        var scheduled = runs.selectById(runs.select(7,1,10).records().getFirst().id());
        plans.update(event(7,2,false,"SKIP"));
        assertThat(runs.selectById(scheduled.id()).snapshot()).isEqualTo(scheduled.snapshot());
        runs.dispatchPending();
        var attempt = runs.selectById(scheduled.id()).attempts().getFirst();
        runs.updateResult(new Result(attempt.id(),scheduled.id(),attempt.leaseVersion(),"FAILED","HANDLER_FAILED","retry",true));
        assertThat(runs.selectById(scheduled.id()).attempts()).hasSize(2);
        assertThat(runs.selectById(scheduled.id()).scheduledAt()).isEqualTo(due);
        var manualA = runs.insert(new Create(UUID.randomUUID(),scheduled.snapshot()));
        var manualB = runs.insert(new Create(UUID.randomUUID(),scheduled.snapshot()));
        assertThat(manualA.id()).isNotEqualTo(manualB.id());
        assertThat(manualA.triggerType()).isEqualTo("MANUAL");
        assertThat(manualA.scheduledAt()).isNull();
        assertThat(runs.select(7,1,10).total()).isEqualTo(3);
    }

    @Test void stopDeleteAndConcurrentConfigurationChangeAreSerializedWithTrigger() throws Exception {
        plans.update(event(7,1,true,"SKIP"));
        var due = plans.selectByJobId(7).nextFireTime();
        when(clock.instant()).thenReturn(due);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(triggers::scanDue);
            var second = executor.submit(() -> plans.update(event(7,2,false,"SKIP")));
            first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS);
        }
        long serializedCount = runs.select(7,1,10).total();
        assertThat(serializedCount).isBetween(0L,1L);
        triggers.scanDue();
        assertThat(runs.select(7,1,10).total()).isEqualTo(serializedCount);
        assertThat(plans.selectByJobId(7).nextFireTime()).isNull();
        var deleted = event(8,1,true,"SKIP");
        plans.update(new PlanSyncRequest(deleted.eventId(),8,1,true,deleted.configuration()));
        when(clock.instant()).thenReturn(BASE.plusSeconds(1000));
        triggers.scanDue();
        assertThat(runs.select(8,1,10).total()).isZero();
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table,Long.class); }
    private PlanSyncRequest event(long jobId,long version,boolean enabled,String policy) {
        return new PlanSyncRequest(UUID.randomUUID(),jobId,version,false,new PlanSyncRequest.Configuration(
                "job-v"+version,null,"demoSuccess","{\"version\":"+version+"}","CRON","0 * * * * *","UTC",enabled,30,2,1,policy));
    }
}

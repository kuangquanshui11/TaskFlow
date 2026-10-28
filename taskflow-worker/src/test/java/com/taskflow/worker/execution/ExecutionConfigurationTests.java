package com.taskflow.worker.execution;

import java.net.URI;
import java.time.Duration;
import com.taskflow.worker.execution.config.ExecutionConfiguration;
import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.service.IExecutionService;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.annotation.Scheduled;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionConfigurationTests {
    private final IExecutionService executions = mock(IExecutionService.class);

    @Test void scanOnlyDispatchesPendingExecutions() {
        new ExecutionConfiguration(properties(true, Duration.ofSeconds(2)), executions).scan();
        verify(executions).executePending();
        verify(executions, never()).reportPending();
        verifyNoMoreInteractions(executions);
    }
    @Test void reportOnlyReportsPendingResults() {
        new ExecutionConfiguration(properties(true, Duration.ofSeconds(2)), executions).report();
        verify(executions).reportPending();
        verify(executions, never()).executePending();
        verifyNoMoreInteractions(executions);
    }
    @Test void disabledScanningSkipsDispatchAndReporting() {
        var configuration = new ExecutionConfiguration(properties(false, Duration.ofSeconds(2)), executions);
        configuration.scan(); configuration.report();
        verifyNoInteractions(executions);
    }
    @Test void reportingUsesDedicatedSingleThreadSchedulerAndFixedDelay() throws Exception {
        var configuration = new ExecutionConfiguration(properties(true, Duration.ofSeconds(2)), executions);
        var scheduler = configuration.workerReportScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("taskflow-report-");
            var report = ExecutionConfiguration.class.getMethod("report").getAnnotation(Scheduled.class);
            var scan = ExecutionConfiguration.class.getMethod("scan").getAnnotation(Scheduled.class);
            assertThat(report.scheduler()).isEqualTo("workerReportScheduler");
            assertThat(scan.scheduler()).isNotEqualTo(report.scheduler());
            assertThat(report.fixedDelayString()).isEqualTo("${taskflow.execution.scan-interval}");
            assertThat(report.initialDelayString()).isEqualTo(scan.initialDelayString());
            assertThat(report.fixedRateString()).isEmpty();
            assertThat(report.fixedRate()).isEqualTo(-1);
        } finally { scheduler.shutdown(); }
    }
    @Test void reportBudgetMustBePositiveAndAtMostThirtySeconds() {
        for (var budget : new Duration[] { null, Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(30).plusNanos(1) }) {
            assertThatThrownBy(() -> properties(true, budget)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Worker 执行参数不合法");
        }
        assertThat(properties(true, Duration.ofMillis(1)).reportBudget()).isEqualTo(Duration.ofMillis(1));
        assertThat(properties(true, Duration.ofSeconds(30)).reportBudget()).isEqualTo(Duration.ofSeconds(30));
    }
    private ExecutionProperties properties(boolean enabled, Duration budget) {
        return new ExecutionProperties(URI.create("http://localhost:8081"), "test-dispatch-token", "test-report-token",
                Duration.ofSeconds(2), Duration.ofSeconds(3), 500, 2, 4, 3, Duration.ofSeconds(2), enabled, budget);
    }
}

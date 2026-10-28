package com.taskflow.admin.jobsync;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.taskflow.admin.jobsync.config.JobSyncConfiguration;
import com.taskflow.admin.jobsync.config.JobSyncProperties;
import com.taskflow.admin.jobsync.service.IJobSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.mock;

class JobSyncSchedulerLifecycleTests {
    @Test void shutdownCancelsFutureScansInsteadOfWaitingForTheirDelay() {
        var configuration = new JobSyncConfiguration(mock(JobSyncProperties.class), mock(IJobSyncService.class));
        var scheduler = configuration.jobSyncScheduler();
        scheduler.initialize();
        var executions = new AtomicInteger();
        var future = scheduler.schedule(executions::incrementAndGet, Instant.now().plusSeconds(60));
        try {
            scheduler.stop();
            assertTimeout(Duration.ofSeconds(2), scheduler::shutdown);
            assertThat(future.isCancelled()).isTrue();
            assertThat(executions).hasValue(0);
        } finally {
            scheduler.getScheduledThreadPoolExecutor().shutdownNow();
        }
    }

    @Test void coordinatedContextShutdownLetsAnActiveSendFinishWithoutInterruption() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closing = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var configuration = new JobSyncConfiguration(mock(JobSyncProperties.class), mock(IJobSyncService.class));
        try (var context = new AnnotationConfigApplicationContext(); var closer = Executors.newSingleThreadExecutor()) {
            context.registerBean("jobSyncScheduler", ThreadPoolTaskScheduler.class, configuration::jobSyncScheduler);
            context.addApplicationListener(event -> { if (event instanceof ContextClosedEvent) closing.countDown(); });
            context.refresh();
            var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            scheduler.execute(() -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException error) { interrupted.set(true); Thread.currentThread().interrupt(); }
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var stopped = closer.submit(context::close);
                assertThat(closing.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(stopped.isDone()).isFalse();
                release.countDown();
                stopped.get(2, TimeUnit.SECONDS);
                assertThat(interrupted).isFalse();
            } finally {
                release.countDown();
            }
        }
    }
}

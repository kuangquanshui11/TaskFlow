package com.taskflow.admin.jobsync.config;

import com.taskflow.admin.jobsync.service.IJobSyncService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobSyncProperties.class)
@EnableScheduling
public class JobSyncConfiguration {
    private final JobSyncProperties properties;
    private final IJobSyncService sync;

    public JobSyncConfiguration(JobSyncProperties properties, IJobSyncService sync) {
        this.properties = properties;
        this.sync = sync;
    }

    @Bean
    public ThreadPoolTaskScheduler jobSyncScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("job-config-sync-");
        // 通过 Spring 生命周期等待当前发送，不在销毁阶段继续运行未来的扫描。
        // late shutdown 会跳过协调停止阶段，多个上下文关闭时可能留下已暂停的扫描线程。
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    // 单实例仅一个发送循环，没有无界异步任务队列；不同实例通过数据库租约竞争。
    @Scheduled(scheduler = "jobSyncScheduler", fixedDelayString = "${taskflow.job-sync.scan-interval}",
            initialDelayString = "${taskflow.job-sync.scan-interval}")
    public void dispatch() {
        if (!properties.enabled()) return;
        try { sync.dispatchPending(); }
        catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Job sync scan failed: {}", error.getClass().getSimpleName());
        }
    }
}

package com.taskflow.scheduler.history.config;

import com.taskflow.scheduler.history.service.IHistoryArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HistoryArchiveProperties.class)
public class HistoryArchiveConfiguration {
    private static final Logger log = LoggerFactory.getLogger(HistoryArchiveConfiguration.class);
    private final HistoryArchiveProperties properties;
    private final IHistoryArchiveService archive;

    public HistoryArchiveConfiguration(HistoryArchiveProperties properties, IHistoryArchiveService archive) {
        this.properties = properties;
        this.archive = archive;
    }

    @Bean
    ApplicationRunner historyArchiveOnStartup() {
        return args -> {
            switch (properties.mode()) {
                case PREVIEW -> execute(true);
                case ONCE -> execute(false);
                default -> { }
            }
        };
    }

    @Bean
    ThreadPoolTaskScheduler historyArchiveScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("history-archive-");
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Scheduled(scheduler = "historyArchiveScheduler", fixedDelayString = "${taskflow.history-archive.interval}",
            initialDelayString = "${taskflow.history-archive.interval}")
    public void archiveOnSchedule() {
        if (properties.mode() == HistoryArchiveProperties.Mode.SCHEDULED) execute(false);
    }

    private void execute(boolean preview) {
        log.info("History archive batch preview={} records={}", preview, archive.runBatch(preview));
    }
}

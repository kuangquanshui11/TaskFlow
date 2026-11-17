package com.taskflow.worker.execution.service.impl;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.taskflow.worker.execution.config.AttemptLogProperties;
import com.taskflow.worker.execution.dto.AttemptLogs;
import com.taskflow.worker.execution.dto.AttemptLogs.Entry;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.execution.mapper.AttemptLogFileMapper;
import com.taskflow.worker.execution.service.IAttemptLogService;
import com.taskflow.worker.node.config.WorkerRegistrationProperties;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AttemptLogServiceImpl implements IAttemptLogService {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_MESSAGE_CHARS = 1000;
    private final AttemptLogProperties properties;
    private final AttemptLogFileMapper files;
    private final String workerId;

    public AttemptLogServiceImpl(AttemptLogProperties properties, AttemptLogFileMapper files, WorkerRegistrationProperties registration) {
        this.properties = properties;
        this.files = files;
        this.workerId = registration.workerId();
    }

    @Override
    public Consumer<String> progress(UUID runId, UUID attemptId) {
        return new Consumer<>() {
            private boolean writable = properties.enabled();
            @Override public synchronized void accept(String message) {
                if (writable && message != null && !message.isBlank())
                    writable = append(runId, attemptId, "INFO", message, false);
            }
        };
    }

    @Override
    public void finished(UUID runId, UUID attemptId, String resultCode) {
        if (properties.enabled())
            append(runId, attemptId, "SUCCEEDED".equals(resultCode) ? "INFO" : "WARN", "执行结束，结果码 " + resultCode, true);
    }

    private boolean append(UUID runId, UUID attemptId, String level, String message, boolean terminal) {
        try {
            if (message.length() > MAX_MESSAGE_CHARS) {
                int end = MAX_MESSAGE_CHARS - 1;
                if (Character.isHighSurrogate(message.charAt(end - 1))) end--;
                message = message.substring(0, end) + "…";
            }
            return files.append(runId, attemptId, new Entry(runId, attemptId, Instant.now(), level, message), terminal);
        } catch (IOException | RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Attempt log write failed. attemptId={}, type={}",
                    attemptId, failure.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public AttemptLogs select(UUID runId, UUID attemptId, String requestedWorkerId, int offset) {
        if (!workerId.equals(requestedWorkerId))
            throw new ExecutionException(409, "WORKER_ID_MISMATCH", "请求目标与本 Worker 标识不一致");
        if (offset < 0) throw new ExecutionException(400, "INVALID_ARGUMENT", "日志偏移量不得为负数");
        try {
            List<Entry> entries = properties.enabled() ? files.read(runId, attemptId, cutoff()) : List.of();
            int start = Math.min(offset, entries.size());
            int end = Math.min(start + PAGE_SIZE, entries.size());
            return new AttemptLogs(runId, attemptId, workerId, entries.subList(start, end), end < entries.size() ? end : null);
        } catch (IOException failure) {
            throw new ExecutionException(503, "ATTEMPT_LOG_UNAVAILABLE", "执行日志暂不可读");
        }
    }

    @Scheduled(scheduler = "workerAttemptLogScheduler", fixedDelayString = "1h", initialDelayString = "1h")
    public void removeExpired() {
        if (!properties.enabled()) return;
        try { files.deleteExpired(cutoff()); }
        catch (IOException failure) {
            LoggerFactory.getLogger(getClass()).warn("Expired attempt log cleanup failed. type={}", failure.getClass().getSimpleName());
        }
    }

    private Instant cutoff() { return Instant.now().minus(properties.retentionDays(), ChronoUnit.DAYS); }
}

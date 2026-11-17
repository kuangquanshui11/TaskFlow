package com.taskflow.worker.execution;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.taskflow.worker.execution.config.AttemptLogProperties;
import com.taskflow.worker.execution.mapper.AttemptLogFileMapper;
import com.taskflow.worker.execution.service.impl.AttemptLogServiceImpl;
import com.taskflow.worker.node.config.WorkerRegistrationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class AttemptLogTests {
    @TempDir Path directory;
    private static final WorkerRegistrationProperties WORKER = new WorkerRegistrationProperties(
            false, "worker-one", "Worker One", URI.create("http://localhost:8081"), "",
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));

    @Test void pagesPersistAcrossRestartAndExpiryOnlyRemovesAttemptLogFiles() throws Exception {
        var runId = UUID.randomUUID();
        var attemptId = UUID.randomUUID();
        var progress = service().progress(runId, attemptId);
        for (int idx = 1; idx <= 105; idx++) progress.accept("第 " + idx + " 批");
        var restarted = service();
        var first = restarted.select(runId, attemptId, "worker-one", 0);
        assertThat(first.entries()).hasSize(100);
        assertThat(first.nextOffset()).isEqualTo(100);
        assertThat(restarted.select(runId, attemptId, "worker-one", first.nextOffset()).entries()).hasSize(5);
        assertThat(restarted.select(runId, UUID.randomUUID(), "worker-one", 0).entries()).isEmpty();
        var log = directory.resolve("worker-one").resolve(runId + "_" + attemptId + ".jsonl");
        var other = Files.writeString(log.resolveSibling("other.jsonl"), "unrelated content");
        var old = FileTime.from(Instant.now().minus(Duration.ofDays(31)));
        Files.setLastModifiedTime(log, old);
        Files.setLastModifiedTime(other, old);
        assertThat(restarted.select(runId, attemptId, "worker-one", 0).entries()).isEmpty();
        restarted.removeExpired();
        assertThat(log).doesNotExist();
        assertThat(other).exists();
    }

    @Test void capsProgressOnceAndKeepsTheResultSummary() throws Exception {
        var runId = UUID.randomUUID();
        var attemptId = UUID.randomUUID();
        var logs = service();
        var progress = logs.progress(runId, attemptId);
        for (int idx = 0; idx < 150; idx++) progress.accept("数".repeat(1100));
        logs.finished(runId, attemptId, "HANDLER_FAILED");
        var page = logs.select(runId, attemptId, "worker-one", 0);
        assertThat(page.entries().stream().filter(entry -> entry.message().contains("容量上限"))).hasSize(1);
        assertThat(page.entries().getLast().message()).contains("HANDLER_FAILED");
        assertThat(Files.size(directory.resolve("worker-one").resolve(runId + "_" + attemptId + ".jsonl")))
                .isLessThanOrEqualTo(256 * 1024);
    }

    private AttemptLogServiceImpl service() {
        var properties = new AttemptLogProperties(true, directory, 30);
        return new AttemptLogServiceImpl(properties,
                new AttemptLogFileMapper(properties, WORKER, JsonMapper.builder().build()), WORKER);
    }
}

package com.taskflow.worker.execution.mapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import com.taskflow.worker.execution.config.AttemptLogProperties;
import com.taskflow.worker.execution.dto.AttemptLogs.Entry;
import com.taskflow.worker.node.config.WorkerRegistrationProperties;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

@Repository
public class AttemptLogFileMapper {
    private static final int MAX_BYTES = 256 * 1024;
    private static final int PROGRESS_BYTES = MAX_BYTES - 8192;
    private static final Pattern LOG_FILE = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"
            + "_[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.jsonl");
    private final Object[] locks = new Object[32];
    private final JsonMapper json;
    private final Path directory;

    public AttemptLogFileMapper(AttemptLogProperties properties, WorkerRegistrationProperties registration, JsonMapper json) {
        this.json = json;
        this.directory = properties.directory().resolve(registration.workerId()).toAbsolutePath().normalize();
        java.util.Arrays.setAll(locks, index -> new Object());
    }

    public boolean append(UUID runId, UUID attemptId, Entry entry, boolean terminal) throws IOException {
        var file = path(runId, attemptId);
        var bytes = encode(entry);
        synchronized (lock(file)) {
            Files.createDirectories(directory);
            long size = Files.isRegularFile(file, NOFOLLOW_LINKS) ? Files.size(file) : 0;
            if (!terminal && size + bytes.length > PROGRESS_BYTES) {
                var notice = encode(new Entry(runId, attemptId, entry.occurredAt(), "WARN", "执行日志达到容量上限，后续进度已省略"));
                if (size + notice.length <= MAX_BYTES - 4096) Files.write(file, notice, CREATE, APPEND, NOFOLLOW_LINKS);
                return false;
            }
            if (size + bytes.length > MAX_BYTES) return false;
            Files.write(file, bytes, CREATE, APPEND, NOFOLLOW_LINKS);
            return true;
        }
    }

    public List<Entry> read(UUID runId, UUID attemptId, Instant cutoff) throws IOException {
        var file = path(runId, attemptId);
        synchronized (lock(file)) {
            try {
                var attributes = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) throw new IOException("Attempt log must be a regular file");
                if (attributes.lastModifiedTime().toInstant().isBefore(cutoff)) return List.of();
                try (var stream = Files.newInputStream(file, READ, NOFOLLOW_LINKS)) {
                    return decode(stream.readNBytes(MAX_BYTES + 1), runId, attemptId);
                }
            } catch (NoSuchFileException missing) { return List.of(); }
        }
    }

    private List<Entry> decode(byte[] bytes, UUID runId, UUID attemptId) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Attempt log size exceeds limit");
        var contents = new String(bytes, StandardCharsets.UTF_8);
        int end = contents.lastIndexOf('\n');
        if (end < 0) return List.of();
        var entries = new ArrayList<Entry>();
        for (var line : contents.substring(0, end).split("\n")) {
            if (line.isBlank()) continue;
            try {
                var entry = json.readValue(line, Entry.class);
                if (entry != null && runId.equals(entry.runId()) && attemptId.equals(entry.attemptId())) entries.add(entry);
            } catch (tools.jackson.core.JacksonException malformed) {
                // 进程退出或磁盘写入失败可能留下不完整记录，其余完整记录仍可查看。
            }
        }
        return entries;
    }

    public void deleteExpired(Instant cutoff) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.newDirectoryStream(directory, file -> LOG_FILE.matcher(file.getFileName().toString()).matches())) {
            for (var file : files) {
                synchronized (lock(file)) {
                    if (Files.isRegularFile(file, NOFOLLOW_LINKS) && expired(file, cutoff)) Files.deleteIfExists(file);
                }
            }
        }
    }

    private boolean expired(Path file, Instant cutoff) throws IOException {
        return Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
    }

    private byte[] encode(Entry entry) {
        // 每次从新行开始，进程异常留下的末行不会吞掉后续记录。
        return ("\n" + json.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private Path path(UUID runId, UUID attemptId) {
        return directory.resolve(runId + "_" + attemptId + ".jsonl");
    }

    private Object lock(Path file) { return locks[Math.floorMod(file.hashCode(), locks.length)]; }
}

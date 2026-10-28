package com.taskflow.admin.jobsync.model;

import java.time.Instant;
import java.util.UUID;
import com.taskflow.admin.job.model.JobDefinition.Configuration;

public final class JobSyncRecords {
    private JobSyncRecords() { }

    public record Event(UUID eventId, long jobId, long configVersion, boolean deleted,
                        Configuration configuration) { }

    public record Delivery(Event event, UUID leaseToken, int attempts) { }

    public record Acknowledgement(UUID eventId, long jobId, long configVersion,
                                  long acceptedVersion, String result) { }

    public record Status(UUID eventId, long jobId, long configVersion, boolean deleted,
                         String state, int attempts, Instant nextAttemptAt, Instant leaseUntil,
                         String lastErrorCode, Long acknowledgedVersion, Instant syncedAt, Instant updatedAt) { }
}

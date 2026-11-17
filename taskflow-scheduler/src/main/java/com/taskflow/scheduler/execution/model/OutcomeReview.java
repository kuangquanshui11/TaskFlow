package com.taskflow.scheduler.execution.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class OutcomeReview {
    private static final Set<String> UNCERTAIN_WITH_OWNER = Set.of("LEASE_EXPIRED", "LEASE_LOST",
            "EXECUTION_TIMEOUT", "HANDLER_INTERRUPTED");
    private OutcomeReview() { }

    public enum Decision { EFFECT_APPLIED, NO_EFFECT, STILL_UNKNOWN }

    public record Case(UUID attemptId, String reason, Instant detectedAt, Review review) {
        public boolean confirmed() { return review != null && review.decision() != Decision.STILL_UNKNOWN; }
    }

    public record Review(UUID requestKey, UUID runId, UUID attemptId, Decision decision,
                         String evidence, String actor, Instant reviewedAt) { }

    public static boolean unknown(String resultCode, boolean hasOwner) {
        return "WORKER_RESTARTED".equals(resultCode) || "RESULT_OUTCOME_UNKNOWN".equals(resultCode)
                || (hasOwner && resultCode != null && UNCERTAIN_WITH_OWNER.contains(resultCode));
    }
}

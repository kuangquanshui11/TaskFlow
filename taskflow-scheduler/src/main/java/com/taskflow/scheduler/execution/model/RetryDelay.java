package com.taskflow.scheduler.execution.model;

import java.time.Duration;
import java.util.random.RandomGenerator;

public final class RetryDelay {
    private RetryDelay() { }

    public static boolean valid(String policy, int intervalSeconds, Integer maxIntervalSeconds) {
        if ("EXPONENTIAL".equals(policy)) {
            return maxIntervalSeconds != null && maxIntervalSeconds >= intervalSeconds && maxIntervalSeconds <= 86400;
        }
        return (policy == null || "FIXED".equals(policy)) && maxIntervalSeconds == null;
    }

    public static Duration calculate(String policy, int intervalSeconds, Integer maxIntervalSeconds,
                                     int completedAttempt, RandomGenerator random) {
        if (!"EXPONENTIAL".equals(policy)) return Duration.ofSeconds(intervalSeconds);
        // maxAttempts 上限为 10，先用 long 计算基准再截断，随机值不会超过配置上限。
        long ceilingMillis = Math.min(maxIntervalSeconds, (long) intervalSeconds << (completedAttempt - 1)) * 1000;
        return Duration.ofMillis(random.nextLong(ceilingMillis * 4 / 5, ceilingMillis + 1));
    }
}

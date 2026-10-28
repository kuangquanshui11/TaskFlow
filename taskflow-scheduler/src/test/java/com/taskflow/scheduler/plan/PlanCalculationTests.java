package com.taskflow.scheduler.plan;

import java.time.Instant;
import com.taskflow.scheduler.plan.dto.PlanSyncRequest.Configuration;
import com.taskflow.scheduler.plan.service.impl.PlanCalculationServiceImpl;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlanCalculationTests {
    private final PlanCalculationServiceImpl calculations = new PlanCalculationServiceImpl();

    @Test void nextIsStrictlyAfterTheReferenceAndSupportsLeapDay() {
        assertThat(next("0 0 9 * * *", "UTC", "2026-10-26T09:00:00Z")).isEqualTo(Instant.parse("2026-10-27T09:00:00Z"));
        assertThat(next("0 0 0 29 2 *", "UTC", "2025-03-01T00:00:00Z")).isEqualTo(Instant.parse("2028-02-29T00:00:00Z"));
    }

    @Test void dstGapSkipsNonexistentLocalTimeAndOverlapRetainsBothOffsets() {
        assertThat(next("0 30 2 * * *", "America/New_York", "2026-03-08T06:59:59Z"))
                .isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
        assertThat(next("0 30 1 * * *", "America/New_York", "2026-11-01T04:00:00Z"))
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(next("0 30 1 * * *", "America/New_York", "2026-11-01T05:45:00Z"))
                .isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
    }

    private Instant next(String cron, String zone, String from) {
        return calculations.calculate(new Configuration("job",null,"demoSuccess",null,"CRON",cron,zone,true,30,3,5,"SKIP"),
                false, Instant.parse(from)).nextFireTime();
    }
}

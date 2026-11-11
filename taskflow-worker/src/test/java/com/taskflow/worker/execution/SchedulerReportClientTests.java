package com.taskflow.worker.execution;

import java.util.Map;
import java.util.UUID;
import com.taskflow.worker.execution.client.SchedulerReportClient;
import com.taskflow.worker.execution.client.SchedulerReportClient.Outcome;
import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Result;
import com.taskflow.worker.remote.client.SchedulerReportRemote;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchedulerReportClientTests {
    @Test void exposesSanitizedHttpCausesAndDistinguishesUnknownTransport() {
        var remote = mock(SchedulerReportRemote.class);
        var properties = mock(ExecutionProperties.class);
        when(properties.reportToken()).thenReturn("test-report-token");
        var client = new SchedulerReportClient(properties, JsonMapper.builder().build(), remote);
        var result = new Result(UUID.randomUUID(), UUID.randomUUID(), 1, "SUCCEEDED", "SUCCEEDED", "done",
                false, "worker-default", UUID.randomUUID());
        var statuses = Map.of(409, Outcome.STALE, 401, Outcome.REPORT_AUTH_FAILED, 400, Outcome.REPORT_REQUEST_REJECTED,
                408, Outcome.REPORT_REMOTE_TIMEOUT, 429, Outcome.REPORT_RATE_LIMITED, 503, Outcome.REPORT_REMOTE_FAILURE);
        for (var entry : statuses.entrySet()) {
            var response = mock(FeignException.class);
            when(response.status()).thenReturn(entry.getKey());
            doThrow(response).when(remote).report(eq(result), anyMap());
            assertThat(client.report(result)).isEqualTo(entry.getValue());
        }
        doThrow(new IllegalStateException("connection lost before confirmation")).when(remote).report(eq(result), anyMap());
        assertThat(client.report(result)).isEqualTo(Outcome.UNCONFIRMED);
        verify(remote, times(statuses.size() + 1)).report(eq(result), anyMap());
    }
}

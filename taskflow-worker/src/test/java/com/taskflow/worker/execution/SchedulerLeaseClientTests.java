package com.taskflow.worker.execution;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import com.taskflow.worker.execution.client.SchedulerLeaseClient;
import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.dto.ExecutionProtocol.LeaseRequest;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.remote.client.SchedulerLeaseRemote;
import feign.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SchedulerLeaseClientTests {
    private final SchedulerLeaseRemote remote = mock(SchedulerLeaseRemote.class);
    private final SchedulerLeaseClient client = new SchedulerLeaseClient(remote,
            new ExecutionProperties(URI.create("http://localhost:8081"), "dispatch", "report",
                    Duration.ofSeconds(2), Duration.ofSeconds(3), 500, 2, 4, 3, Duration.ofSeconds(2), false, Duration.ofSeconds(2)));
    private final LeaseRequest request = new LeaseRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "worker-1", UUID.randomUUID());

    @ParameterizedTest @ValueSource(ints = {408, 429, 500, 503})
    void uncertainFailureIsNotClassifiedAsPermanentAndNoPostIsReplayed(int status) {
        var failure = failure(status);
        when(remote.updateLease(any(), any())).thenThrow(failure);
        assertThatThrownBy(() -> client.updateLease(request)).isSameAs(failure);
        verify(remote, times(1)).updateLease(eq(request), argThat(headers -> "report".equals(headers.get("X-Taskflow-Token"))));
    }
    @ParameterizedTest @ValueSource(ints = {400, 401, 404, 409})
    void definiteRejectionImmediatelyRevokesQualification(int status) {
        when(remote.updateLease(any(), any())).thenThrow(failure(status));
        assertThatThrownBy(() -> client.updateLease(request)).isInstanceOfSatisfying(ExecutionException.class,
                rejected -> assertThat(rejected.code()).isEqualTo("LEASE_REJECTED"));
        verify(remote, times(1)).updateLease(any(), any());
    }
    private FeignException failure(int status) {
        var http = Request.create(Request.HttpMethod.POST, "http://localhost/internal/v1/attempts/updateLease",
                java.util.Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("lease", Response.builder().request(http).status(status).reason("test failure").build());
    }
}

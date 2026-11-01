package com.taskflow.worker.execution;

import java.time.*;
import java.util.*;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.exception.HandlerFailureException;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.execution.service.ITaskHandler;
import com.taskflow.worker.execution.service.impl.HandlerRegistryServiceImpl;
import com.taskflow.worker.order.config.OrderHandlerProperties;
import com.taskflow.worker.order.service.impl.OrderTimeoutHandlerServiceImpl;
import com.taskflow.worker.remote.client.OrderTimeoutRemote;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderTimeoutHandlerTests {
    final OrderTimeoutRemote remote=mock(OrderTimeoutRemote.class);
    final ExecutionContext context=mock(ExecutionContext.class);
    final OrderTimeoutHandlerServiceImpl handler=new OrderTimeoutHandlerServiceImpl(remote,
            new OrderHandlerProperties(true,"test-token",Duration.ofSeconds(1),Duration.ofSeconds(2)));
    Snapshot snapshot(String payload) { return new Snapshot(1,1,"orders","orderTimeoutCheck",new JsonMapper().readTree(payload),60,3,1,"MANUAL",null,"UTC","SKIP",false,null); }
    void active() { when(context.executionDeadline()).thenReturn(Instant.now().plusSeconds(60));when(context.businessIdempotencyKey(anyString())).thenReturn("run:batch"); }
    @Test void registryRejectsDuplicateKeysAndIncludesBusinessHandler() {
        assertThat(new HandlerRegistryServiceImpl(List.of(handler)).keys()).containsExactly("orderTimeoutCheck");
        assertThatThrownBy(()->new HandlerRegistryServiceImpl(List.of(handler,handler))).isInstanceOf(IllegalStateException.class);
    }
    @Test void batchesPassCursorAndSummarizeResults() {
        active();var cutoff=Instant.now();
        when(remote.close(any(),any())).thenReturn(new OrderTimeoutRemote.Result(2,1,1,0,cutoff,cutoff,"a"),
                new OrderTimeoutRemote.Result(1,1,0,0,cutoff,cutoff,"b"));
        assertThat(handler.execute(snapshot("{\"batchSize\":2,\"maxBatches\":3}"),context)).contains("scanned=3, closed=2, skipped=1, failed=0");
        verify(remote).close(eq(new OrderTimeoutRemote.Batch(2,cutoff,cutoff,"a")),any());
    }
    @Test void invalidPayloadNeverCallsRemote() {
        for(String payload:List.of("{\"batchSize\":0}","{\"maxBatches\":1.5}","{\"timeoutMinutes\":30}"))
            assertThatThrownBy(()->handler.execute(snapshot(payload),context)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(remote);
    }
    @Test void remoteClientErrorsAreNotRetriedAndServerFailuresAreRetryable() {
        active();
        var request=feign.Request.create(feign.Request.HttpMethod.POST,"http://test",Map.of(),null,java.nio.charset.StandardCharsets.UTF_8,null);
        for(int status:List.of(400,401,403,409,429,500,503)) {
            doThrow(feign.FeignException.errorStatus("close",feign.Response.builder().request(request).status(status).headers(Map.of()).build())).when(remote).close(any(),any());
            assertThatThrownBy(()->handler.execute(snapshot("{}"),context)).isInstanceOfSatisfying(HandlerFailureException.class,
                    error->assertThat(error.retryable()).isEqualTo(status==429 || status>=500));
        }
    }
    @Test void partialFailuresProduceRetryableFailureAndEmptyBatchSucceeds() {
        active();var now=Instant.now();
        when(remote.close(any(),any())).thenReturn(new OrderTimeoutRemote.Result(1,0,0,1,now,now,"a"));
        assertThatThrownBy(()->handler.execute(snapshot("{}"),context)).isInstanceOfSatisfying(HandlerFailureException.class,e->assertThat(e.retryable()).isTrue());
        when(remote.close(any(),any())).thenReturn(new OrderTimeoutRemote.Result(0,0,0,0,now,null,null));
        assertThat(handler.execute(snapshot("{}"),context)).contains("closed=0");
    }
}

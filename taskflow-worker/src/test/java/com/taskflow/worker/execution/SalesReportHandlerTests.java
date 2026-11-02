package com.taskflow.worker.execution;

import java.time.*;
import java.util.*;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.execution.exception.HandlerFailureException;
import com.taskflow.worker.order.config.OrderHandlerProperties;
import com.taskflow.worker.remote.client.SalesReportRemote;
import com.taskflow.worker.sales.service.impl.SalesReportHandlerServiceImpl;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SalesReportHandlerTests {
    final SalesReportRemote remote=mock(SalesReportRemote.class);
    final ExecutionContext context=mock(ExecutionContext.class);
    final SalesReportHandlerServiceImpl handler=new SalesReportHandlerServiceImpl(remote,
            new OrderHandlerProperties(false,"test-token",Duration.ofSeconds(1),Duration.ofSeconds(2)));
    Snapshot snapshot(String payload) { return new Snapshot(1,1,"report","salesReport",new JsonMapper().readTree(payload),60,3,1,"MANUAL",null,"Asia/Shanghai","SKIP",false,null); }
    void active() {
        when(context.executionDeadline()).thenReturn(Instant.now().plusSeconds(60));
        when(context.businessIdempotencyKey("sales-report")).thenReturn("stable-run:sales-report");
    }
    @Test void parametersAndBusinessKeyReachServiceAndSummary() {
        active();
        var day=LocalDate.of(2026,10,1);
        when(remote.insert(any(),any())).thenReturn(new SalesReportRemote.Report(UUID.randomUUID().toString(),8,day,"Asia/Shanghai",2,3,30000,Instant.now()));
        assertThat(handler.execute(snapshot("{\"shopId\":8,\"businessDate\":\"2026-10-01\"}"),context)).contains("amountCents=30000","orders=2");
        verify(remote).insert(eq(new SalesReportRemote.Generate("stable-run:sales-report",8,day,"Asia/Shanghai")),any());
        assertThat(handler.keys()).containsExactly("salesReport");
    }
    @Test void invalidInputDoesNotSendRequestAndBudgetIsEnforced() {
        for(var payload:List.of("{}","{\"shopId\":0}","{\"shopId\":1.5}","{\"shopId\":8,\"businessDate\":true}"))
            assertThatThrownBy(()->handler.execute(snapshot(payload),context)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(remote);
        active(); when(context.executionDeadline()).thenReturn(Instant.now());
        assertThatThrownBy(()->handler.execute(snapshot("{\"shopId\":8}"),context)).isInstanceOf(HandlerFailureException.class);
        verifyNoInteractions(remote);
    }
    @Test void failuresBecomePersistentAttemptRetryWithoutTransportReplay() {
        active();
        var request=feign.Request.create(feign.Request.HttpMethod.POST,"http://test",Map.of(),null,java.nio.charset.StandardCharsets.UTF_8,null);
        for(int code:List.of(400,401,403,404,409,408,429,500,503)) {
            doThrow(feign.FeignException.errorStatus("insert",feign.Response.builder().request(request).status(code).headers(Map.of()).build())).when(remote).insert(any(),any());
            assertThatThrownBy(()->handler.execute(snapshot("{\"shopId\":8}"),context)).isInstanceOfSatisfying(HandlerFailureException.class,
                    error->assertThat(error.retryable()).isEqualTo(code==408 || code==429 || code>=500));
        }
    }
}

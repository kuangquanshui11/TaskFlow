package com.taskflow.admin.sales;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import com.taskflow.admin.remote.client.SalesReportRemote;
import com.taskflow.admin.sales.dto.SalesResponse.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers=com.taskflow.admin.support.IsolatedH2ContextInitializer.class)
@org.springframework.context.annotation.Import(com.taskflow.admin.support.TestSessionConfiguration.class)
@TestPropertySource(properties="taskflow.sales.token=test-sales-query")
class SalesReportApiTests {
    @Value("${local.server.port}") int port;
    @MockitoBean SalesReportRemote remote;
    @Autowired JsonMapper json;
    @Test void viewerCanQueryAndExportWithoutLocalSqlTransaction() throws Exception {
        var report=new Report(UUID.randomUUID().toString(),8,LocalDate.of(2026,10,1),"Asia/Shanghai",2,2,30000,Instant.now());
        when(remote.select(anyMap(),anyMap())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(call.<Map<String,String>>getArgument(1)).containsEntry("X-Taskflow-Token","test-sales-query");
            return new Page(List.of(report),1,1,20);
        });
        assertThat(get("select?shopId=8",true).statusCode()).isEqualTo(200);
        when(remote.detail(eq(report.id()),anyMap())).thenReturn(new Detail(report,List.of(new Item(1,"测试商品",2,2,30000))));
        assertThat(get("selectById?id="+report.id(),true).statusCode()).isEqualTo(200);
        when(remote.export(eq(report.id()),anyMap())).thenReturn(new Export("sales-8-2026-10-01.csv","total,30000"));
        var exported=get("selectExport?id="+report.id(),true);
        assertThat(exported.statusCode()).isEqualTo(200);
        assertThat(json.readTree(exported.body()).path("data").path("content").asString()).contains("30000");
    }
    @Test void noRoleInvalidInputAndRemoteFailureDoNotBecomeSuccessfulEmptyReports() throws Exception {
        assertThat(get("select",false).statusCode()).isEqualTo(403);
        assertThat(get("select?page=0",true).statusCode()).isEqualTo(400);
        assertThat(get("selectById?id=invalid",true).statusCode()).isEqualTo(400);
        verifyNoInteractions(remote);
        when(remote.select(anyMap(),anyMap())).thenReturn(new Page(null,0,1,20));
        assertThat(get("select",true).statusCode()).isEqualTo(503);
        when(remote.select(anyMap(),anyMap())).thenThrow(new IllegalStateException("offline"));
        assertThat(get("select",true).statusCode()).isEqualTo(503);
    }
    private HttpResponse<String> get(String path,boolean reader) throws Exception {
        String token=com.taskflow.admin.support.TestTokens.token(Map.of("resource_access",Map.of("taskflow-api",
                Map.of("roles",reader?List.of("VIEWER"):List.of()))));
        com.taskflow.admin.support.TestSessionConfiguration.register(token);
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/sales-reports/"+path))
                .header("Authorization","Bearer "+token).GET().build();
        try(var client=HttpClient.newHttpClient()) { return client.send(request,HttpResponse.BodyHandlers.ofString()); }
    }
}

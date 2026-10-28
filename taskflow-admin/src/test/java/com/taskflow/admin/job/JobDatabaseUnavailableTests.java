package com.taskflow.admin.job;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.security.test.context.support.WithMockUser(authorities = {"job:write", "job:delete", "job:operate", "run:trigger", "job-sync:retry"})
@org.springframework.context.annotation.Import(com.taskflow.admin.support.TestSessionConfiguration.class)
class JobDatabaseUnavailableTests {
    @Autowired
    private HikariDataSource dataSource;
    @Autowired
    private JsonMapper json;
    @Value("${local.server.port}")
    private int port;

    @Test
    void unavailableDatabaseStillReturnsTheDocumentedServiceUnavailableResponse() throws Exception {
        // 只关闭当前测试上下文的 H2 连接池，不停止外部数据库。
        assertThat(dataSource.getJdbcUrl()).startsWith("jdbc:h2:mem:taskflow-admin-");
        dataSource.close();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/jobs/selectById?id=1")).header("Authorization", "Bearer " + com.taskflow.admin.support.TestTokens.admin())
                .timeout(Duration.ofSeconds(10)).GET().build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(json.readTree(response.body()).get("code").stringValue()).isEqualTo("DATABASE_UNAVAILABLE");
            assertThat(response.body()).doesNotContain("SELECT", "jdbc:", "HikariPool");
        }
    }
}

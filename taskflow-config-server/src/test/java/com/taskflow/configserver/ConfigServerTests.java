package com.taskflow.configserver;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"native","test"})
class ConfigServerTests {
    @Value("${local.server.port}") int port;
    @Value("${taskflow.config.username}") String user;
    @Value("${taskflow.config.password}") String password;
    @Test void configIsAuthenticatedAndReturnsActualRepositoryValues() throws Exception {
        assertThat(get(false).statusCode()).isEqualTo(401);
        var response=get(true);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("isolated-test-v1").contains("taskflow.platform.config-revision");
        assertThat(response.body()).doesNotContain(password);
    }
    private HttpResponse<String> get(boolean authenticated) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/taskflow-admin/cloud"));
        if(authenticated) request.header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8)));
        try(var client=HttpClient.newHttpClient()) { return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString()); }
    }
}

package com.taskflow.registry;

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
@ActiveProfiles("test")
class RegistryTests {
    @Value("${local.server.port}") int port;
    @Value("${taskflow.registry.username}") String user;
    @Value("${taskflow.registry.password}") String password;
    @Test void registryRequiresCredentialsWhileHealthIsPublic() throws Exception {
        assertThat(get("/eureka/apps",false)).isEqualTo(401);
        assertThat(get("/eureka/apps",true)).isEqualTo(200);
        assertThat(get("/actuator/health",false)).isEqualTo(200);
    }
    private int get(String path,boolean authenticated) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Accept","application/json");
        if (authenticated) request.header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8)));
        try(var client=HttpClient.newHttpClient()) { return client.send(request.GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode(); }
    }
}

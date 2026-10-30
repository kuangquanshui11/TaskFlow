package com.taskflow.admin.access.config;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.http.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;
import com.taskflow.admin.access.model.AccessPolicy;
import com.taskflow.admin.system.dto.ApiResponse;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({AccessProperties.class, SessionProperties.class})
public class AccessSecurityConfiguration {
    @Bean
    JwtDecoder accessJwtDecoder(AccessProperties properties, ResourceLoader resources) throws IOException {
        NimbusJwtDecoder decoder;
        if (properties.publicKeyLocation() != null && !properties.publicKeyLocation().isBlank()) {
            try (var input = resources.getResource(properties.publicKeyLocation()).getInputStream()) {
                decoder = NimbusJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(input))
                        .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            }
        } else {
            decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<java.util.List<String>>("aud", aud -> aud != null && aud.contains(properties.audience())),
                new JwtClaimValidator<String>("sub", sub -> sub != null && !sub.isBlank()),
                new JwtClaimValidator<java.time.Instant>("exp", exp -> exp != null)));
        return decoder;
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain accessSecurity(HttpSecurity http, AccessProperties properties, JsonMapper json,
            com.taskflow.admin.access.service.IAccessSessionService sessions) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> AccessPolicy.authorities(jwt, properties.audience()));
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // This chain only accepts explicitly supplied Bearer headers, never cookie authentication.
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/readiness",
                                "/actuator/health/liveness", "/api/v1/access/selectConfiguration").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/access/selectCurrent", "/api/v1/hello").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/access/insert", "/api/v1/access/update", "/api/v1/access/disable").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/jobs/select", "/api/v1/jobs/selectById",
                                "/api/v1/jobs/selectSummary", "/api/v1/job-handlers/select",
                                "/api/v1/job-sync/selectByJobId", "/api/v1/job-sync/selectPlanByJobId").hasAuthority("job:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/jobs/preview").hasAuthority("job:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/jobs/insert", "/api/v1/jobs/update").hasAuthority("job:write")
                        .requestMatchers(HttpMethod.POST, "/api/v1/jobs/delete").hasAuthority("job:delete")
                        .requestMatchers(HttpMethod.POST, "/api/v1/jobs/enable", "/api/v1/jobs/disable").hasAuthority("job:operate")
                        .requestMatchers(HttpMethod.POST, "/api/v1/runs/insert").hasAuthority("run:trigger")
                        .requestMatchers(HttpMethod.GET, "/api/v1/runs/select", "/api/v1/runs/selectById",
                                "/api/v1/runs/selectByRequestKey").hasAuthority("run:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/job-sync/updateRetry").hasAuthority("job-sync:retry")
                        .requestMatchers(HttpMethod.GET, "/api/v1/config-reconciliation/selectById",
                                "/api/v1/config-reconciliation/selectRepairById").hasAuthority("job:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/config-reconciliation/insert").hasAuthority("job-sync:reconcile")
                        .requestMatchers(HttpMethod.POST, "/api/v1/config-reconciliation/updateRepair").hasAuthority("job-sync:repair")
                        .requestMatchers(HttpMethod.GET, "/api/v1/workers/select", "/api/v1/workers/selectById").hasAuthority("worker:read")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, failure) -> error(response, json, 401)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> error(response, json, 401))
                        .accessDeniedHandler((request, response, failure) -> error(response, json, 403)));
        http.addFilterAfter(new com.taskflow.admin.access.filter.AccessSessionFilter(sessions, json),
                org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private static void error(HttpServletResponse response, JsonMapper json, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        String requestId = response.getHeader("X-Request-Id");
        if (requestId == null) { requestId = UUID.randomUUID().toString(); response.setHeader("X-Request-Id", requestId); }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("code", status == 401 ? "UNAUTHENTICATED" : "ACCESS_DENIED");
        body.put("message", status == 401 ? "请登录后重试" : "当前账号没有此操作权限");
        body.put("data", null); body.put("requestId", requestId);
        response.getOutputStream().write(json.writeValueAsBytes(body));
    }
}

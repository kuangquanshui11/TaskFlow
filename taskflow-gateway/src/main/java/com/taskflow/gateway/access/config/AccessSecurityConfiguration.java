package com.taskflow.gateway.access.config;

import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import com.taskflow.gateway.access.exception.AccessResponseWriter;

@Configuration
@EnableConfigurationProperties(AccessProperties.class)
public class AccessSecurityConfiguration {
    @Bean
    ReactiveJwtDecoder accessJwtDecoder(AccessProperties properties, ResourceLoader resources) throws IOException {
        NimbusReactiveJwtDecoder decoder;
        if (properties.publicKeyLocation() != null && !properties.publicKeyLocation().isBlank()) {
            try (var input = resources.getResource(properties.publicKeyLocation()).getInputStream()) {
                decoder = NimbusReactiveJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(input))
                        .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            }
        } else {
            decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri())
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
    SecurityWebFilterChain accessSecurity(ServerHttpSecurity http, AccessResponseWriter writer) {
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .requestCache(cache -> cache.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/readiness",
                                "/actuator/health/liveness", "/api/v1/access/selectConfiguration").permitAll()
                        .pathMatchers("/api/v1/**").authenticated().anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})
                        .authenticationEntryPoint((exchange, failure) -> writer.write(exchange, 401, "UNAUTHENTICATED", "请登录后重试")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, failure) -> writer.write(exchange, 401, "UNAUTHENTICATED", "请登录后重试"))
                        .accessDeniedHandler((exchange, failure) -> writer.write(exchange, 403, "ACCESS_DENIED", "当前账号没有此操作权限")))
                .build();
    }
}

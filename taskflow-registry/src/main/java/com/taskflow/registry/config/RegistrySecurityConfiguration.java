package com.taskflow.registry.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
public class RegistrySecurityConfiguration {
    @Bean UserDetailsService registryUsers(@Value("${taskflow.registry.username}") String username,@Value("${taskflow.registry.password}") String password) {
        if (username.isBlank() || password.length()<24) throw new IllegalArgumentException("注册中心需要至少 24 位的独立随机密码");
        var encoder=org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername(username).password(encoder.encode(password)).roles("REGISTRY").build());
    }
    @Bean SecurityFilterChain registrySecurity(HttpSecurity http) throws Exception {
        return http.csrf(c->c.ignoringRequestMatchers("/eureka/**"))
                .authorizeHttpRequests(a->a.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults()).sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS)).build();
    }
}

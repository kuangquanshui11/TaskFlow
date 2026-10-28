package com.taskflow.configserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
public class ConfigSecurityConfiguration {
    @Bean UserDetailsService configUsers(@Value("${taskflow.config.username}") String username,@Value("${taskflow.config.password}") String password) {
        if (username.isBlank() || password.length()<24) throw new IllegalArgumentException("配置中心需要至少 24 位的独立随机密码");
        var encoder=org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername(username).password(encoder.encode(password)).roles("CONFIG_READER").build());
    }
    @Bean SecurityFilterChain configSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a->a.requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/**").authenticated().anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults()).sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS)).build();
    }
}

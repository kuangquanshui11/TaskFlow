package com.taskflow.admin.job.config;

import java.time.Clock;

import com.taskflow.admin.job.mapper.redis.RedisJobCacheMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobDetailCacheProperties.class)
public class JobDetailCacheConfiguration {
    @Bean
    RedisJobCacheMapper jobCacheMapper(StringRedisTemplate redis, JsonMapper json,
                                     JobDetailCacheProperties properties) {
        return new RedisJobCacheMapper(redis, json, properties, Clock.systemUTC());
    }
}

package com.taskflow.admin.run.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunClientProperties.class)
public class RunClientConfiguration { }

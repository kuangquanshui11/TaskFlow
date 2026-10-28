package com.taskflow.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class TaskFlowRegistryApplication {
    public static void main(String[] args) { SpringApplication.run(TaskFlowRegistryApplication.class,args); }
}

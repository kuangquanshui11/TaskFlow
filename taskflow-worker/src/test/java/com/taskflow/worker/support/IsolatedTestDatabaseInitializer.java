package com.taskflow.worker.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** 在创建数据源和执行 Flyway 之前拒绝外部配置将测试指向真实数据库。 */
public class IsolatedTestDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        var url = context.getEnvironment().getProperty("spring.datasource.url", "");
        if (!url.startsWith("jdbc:h2:mem:taskflow-worker-"))
            throw new IllegalStateException("worker 默认测试只能使用独立 H2 内存库，已在创建数据源之前拒绝启动");
    }
}

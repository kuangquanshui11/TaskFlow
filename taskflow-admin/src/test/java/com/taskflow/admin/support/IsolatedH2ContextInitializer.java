package com.taskflow.admin.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** 在创建 DataSource、执行 Flyway 或测试建表脚本之前检查配置，而非仅在测试方法前检查。 */
public class IsolatedH2ContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        var environment = context.getEnvironment();
        var url = environment.getProperty("spring.datasource.url", "");
        if (!url.startsWith("jdbc:h2:mem:taskflow-admin-")) {
            throw new IllegalStateException("Redis 测试只能使用独立 H2 内存数据库，已在创建数据源前拒绝启动");
        }
        if (environment.getProperty("spring.flyway.enabled", Boolean.class, true)) {
            throw new IllegalStateException("Redis 测试必须关闭生产 Flyway 迁移");
        }
    }
}

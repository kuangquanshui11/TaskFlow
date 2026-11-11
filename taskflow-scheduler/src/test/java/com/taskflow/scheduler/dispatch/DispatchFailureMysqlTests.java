package com.taskflow.scheduler.dispatch;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@ActiveProfiles("test-run-state-mysql")
@ContextConfiguration(inheritInitializers = false, initializers = DispatchFailureMysqlTests.IsolatedDatabase.class)
@EnabledIfEnvironmentVariable(named = "TASKFLOW_RUN_STATE_TEST_DB_SCHEMA", matches = "taskflow_it_run_state_[a-f0-9]{32}")
class DispatchFailureMysqlTests extends DispatchFailureTests {
    static class IsolatedDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            var schema = env.getRequiredProperty("TASKFLOW_RUN_STATE_TEST_DB_SCHEMA");
            var port = env.getRequiredProperty("TASKFLOW_RUN_STATE_TEST_DB_PORT");
            var url = env.getProperty("spring.datasource.url", "");
            if (!schema.matches("taskflow_it_run_state_[a-f0-9]{32}") || !port.matches("[0-9]{4,5}")
                    || !url.startsWith("jdbc:mysql://127.0.0.1:" + port + "/" + schema + "?")) {
                throw new IllegalStateException("Dispatch failure tests require an isolated loopback MySQL schema");
            }
        }
    }
}

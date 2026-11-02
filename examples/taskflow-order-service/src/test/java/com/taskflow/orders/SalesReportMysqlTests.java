package com.taskflow.orders;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
@ActiveProfiles({"test","test-mysql"})
@EnabledIfEnvironmentVariable(named="TASKFLOW_ORDER_TEST_DB_SCHEMA",matches="taskflow_it_order_[a-zA-Z0-9]+")
class SalesReportMysqlTests extends SalesReportTests { }

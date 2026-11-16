# P05 验证记录

## 异步追踪

2026-11-16（Asia/Shanghai），Windows、Java 21、Maven 3.10.0，业务模块使用隔离 H2。消息发布和消费测试分别使用模拟 RabbitTemplate 和 Channel。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `mvn -o -pl taskflow-admin,taskflow-scheduler,taskflow-worker -Dtest=RunExecutionTests,RunStateConcurrencyTests,DispatchFairnessTests,PlanTriggerTests,RabbitDispatchPublisherTests,DispatchConsumerTests,WorkerExecutionTests,RunApiTests,JobSyncTests -Dsurefire.failIfNoSpecifiedTests=false test` | Admin 16 项、Scheduler 48 项通过。Worker 首轮有 2 项事件期望未包含新增的 Inbox 事件 | `.tools/p05-trace-tests.log` |
| `mvn -o -pl taskflow-worker -Dtest=DispatchConsumerTests,WorkerExecutionTests test` | Worker 35 项通过，包括持久化 W3C 上下文、重复投递保留原上下文、提交后 ACK、重启恢复和补报 | `.tools/p05-worker-trace-tests.log` |

真实 RabbitMQ 下同一 Trace 的异步 OTLP 导出和 Jaeger 查询有待独立验收。

## 耗时分解

2026-11-16（Asia/Shanghai），Windows、Java 21、Maven 3.10.0、隔离 H2。Scheduler 34 项和 Worker 36 项通过，包括初始化及迁移、快速结果先于派发确认、重试、Handler 执行计时和结果上报。证据为 `.tools/p05-timing-tests.log`，命令为 `mvn -o -pl taskflow-scheduler,taskflow-worker -Dtest=RunExecutionTests,RunSummaryTests,RunReportingMigrationTests,DispatchFailureTests,WorkerExecutionTests,ExecutionLeaseTests,ResultPersistenceTests -Dsurefire.failIfNoSpecifiedTests=false test`。Web 使用 `npm test` 和 `npm run build`，单元测试和构建通过。

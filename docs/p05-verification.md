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

## 操作审计与根构建

2026-11-16（Asia/Shanghai），Windows、Java 21.0.12.1、Maven Wrapper 3.10.0。

| 命令或步骤 | 结果 | 证据 |
| --- | --- | --- |
| `mvnw.cmd -o -B -pl taskflow-admin,taskflow-scheduler -Dtest=RunApiTests,ReconciliationTests,DispatchOutboxTests -Dsurefire.failIfNoSpecifiedTests=false test` | 29 项通过。验证了五类 Job 操作、版本冲突审计、普通手动触发响应丢失后只读对账、认证主体匹配、查询权限和 Outbox 事务回滚 | `.tools/p05-audit-tests.log` |
| `mvnw.cmd -o -B -fae verify` | 404 项通过、52 项条件跳过，构建成功 | `.tools/p05-root-verify.log` |
| MySQL 8.0.34 独立回环实例，按版本在三个空 schema 执行各服务 Flyway SQL | 28 项脚本全部成功，含 Admin V6–V7、Scheduler V14–V15、Worker V5–V6 | `.tools/p05-mysql-20261116-185926/migrations.log` |

Maven 使用本机中央仓库依赖缓存，验证命令引用空的用户及全局设置文件以覆盖本机镜像配置。MySQL 检查覆盖迁移脚本执行，审计业务场景在隔离 H2 测试中验证。

Scheduler 将创建日志移至 Run 事务提交后，复跑 `RunExecutionTests,RunStateConcurrencyTests,DispatchOutboxTests` 共 38 项，全部通过。证据为 `.tools/p05-scheduler-final.log`。

Feign 熔断线程配置调整后，在 Admin、Scheduler、Worker 复跑 `RemoteGovernanceTests,RunExecutionTests,DispatchConsumerTests,SchedulerReportClientTests`，29 项全部通过。证据为 `.tools/p05-final-propagation-tests.log`。

## 独立进程与浏览器

2026-11-16（Asia/Shanghai），Windows、Java 21、独立 H2、真实 Eureka/Config Server、直派 HTTP、OTLP 收集器及 Chrome。

- P05 专项副本沿用 `tools/test-platform.ps1 -SkipBuild` 的环境启动与隔离流程，执行手动触发、真实结果上报、审计查询和业务重试。Gateway、Admin、Scheduler、Worker 实际导出的 Trace ID 一致。Run `da6ba4b8-28a2-4739-adc6-9c714916fb0a` 的创建、派发、Inbox、Handler 和上报日志均关联 Trace `a4342dd7bcb84fa0a32cf3af2b23d3bd`。证据为 `.tools/p05-focused-platform.log`、`.tools/platform-e2e/20261116-192508-535/traces/` 和 `.tools/execution-e2e/20261116-192527-100/`。
- 全量平台脚本的令牌刷新步骤未完成。专项副本跳过该步骤。首次执行两个浏览器文件时，独立详情通过，既有失败统计返回用例刷新页面后跳转到身份登录页。
- 现有 RabbitMQ 集群的三个管理端点未连通。真实 Broker 重投、Worker 重启补报的 Trace 和 Jaeger 界面仍待验收。
- 最终专项使用 `.tools/p05-platform-focused.ps1 -SkipBuild`，Chrome 过滤到「独立运行详情」和「工作台运行概览」。20 项独立进程检查、2 项浏览器用例和 30 项前端单元测试通过，Vite 构建成功。浏览器验证桌面和移动端，以及工作台 P95 数值和说明采用同一端到端口径。证据为 `.tools/p05-focused-browser-final.log`、`.tools/execution-e2e/20261116-193504-479/`、`.tools/platform-e2e/20261116-193443-837/`。

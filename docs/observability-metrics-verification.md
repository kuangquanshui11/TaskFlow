# 积压与延迟指标验证记录

日期：2026-10-31（Asia/Shanghai）。环境：Windows、PowerShell 7.6.5、Temurin JDK 21.0.12.1、Maven Wrapper 3.10.0、Spring Boot 4.1.1。自动化测试使用 test profile、IsolatedTestDatabaseInitializer 和独立 H2 内存库，不访问业务 MySQL、Redis 或 MQ。

## 命令与结果

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `.\mvnw.cmd -pl taskflow-scheduler -am test` | 128 项，128 通过，0 失败/错误/跳过 | `.tools/metrics-scheduler-test.log` |
| `.\mvnw.cmd -pl taskflow-worker -am test` | 74 项，73 通过，0 失败/错误，1 条件跳过 | `.tools/metrics-worker-test.log` |
| `.\mvnw.cmd verify` | 六个应用 BUILD SUCCESS；312 项，304 通过，0 失败/错误，8 条件跳过；耗时 3 分 41 秒 | `.tools/metrics-root-verify.log` |
| `.\mvnw.cmd clean verify` | 六个应用 BUILD SUCCESS；312 项，304 通过，0 失败/错误，8 条件跳过；耗时 3 分 34 秒 | `.tools/metrics-clean-verify.log` |

Worker 跳过项为真实 Broker 背压用例，未设置 TASKFLOW_TEST_MQ_VHOST。首次构建受到沙箱禁止写入本机 Maven 缓存锁文件的限制，授权后使用原命令运行。开发中修正了 Clock 导入歧义及 SimpleMeterRegistry 非 AutoCloseable 的编译问题，表中为修正后的实际结果。最终源代码的计数 Gauge 不设 baseUnit，由根 verify 验证。

## 注入积压与断言

新增 Scheduler DispatchMetricsTests 7 项、MetricsConfigurationTests 4 项；Worker WorkerMetricsTests 4 项、MetricsConfigurationTests 4 项，新增共 19 项。

| 指标 / 场景 | 注入与实际断言 | 结果 |
| --- | --- | --- |
| taskflow.dispatch.outbox.backlog | 7 WAITING、3 READY（其中 2 到期）、1 SENDING、各 1 PUBLISHED/FAILED/CANCELLED；WAITING=7、READY=3 | 通过 |
| taskflow.dispatch.outbox.publish.overdue | 2 到期 READY、1 未来 READY；值=2 | 通过 |
| taskflow.dispatch.outbox.claim.expired | 1 SENDING 且 claim_until 已过期；值=1 | 通过 |
| taskflow.dispatch.attempt.dispatch.overdue | READY 与 DISPATCHING 各 1，next_dispatch_at=now；值=2 | 通过 |
| taskflow.dispatch.attempt.lease.expired | RUNNING 且 expires_at=now；值=1 | 通过 |
| taskflow.dispatch.attempt.lease.expiring | RUNNING 且 expires_at=now+60s；值=1；now+61s 不计入 | 通过 |
| Attempt 终态排除 | SUCCEEDED/FAILED/TIMED_OUT 各 2（过去与未来 30s）；共 6 条不计入上述三项 | 通过 |
| taskflow.worker.node.heartbeat.age | 2 在线节点，心跳年龄 0/120 秒；另 1 节点 expires_at=now，心跳年龄 600 秒；值在 [115,130]（可控时钟下 120） | 通过 |
| taskflow.worker.result.unreported | SUCCEEDED/FAILED 各 1 未报、各 1 已报；值=2；重报次数 99、next_report_at 为未来，仍统计未报终态 | 通过 |
| taskflow.worker.execution.backlog | 3 ACCEPTED、1 RUNNING；值=3 | 通过 |
| taskflow.metrics.sample | 两服务空库成功后 ok +1；末条查询注入异常后 failed +1、ok 不变 | 通过 |
| taskflow.metrics.sample.age | 成功时为 0；推进 Clock 30 秒并注入异常后为 30，Gauge 读取不访问 Mapper | 通过 |
| 首次未知与空库 | 所有 Gauge 构造后及首次失败后为 -1；空库成功后积压为 0，无在线节点心跳年龄保持 -1 | 通过 |
| 失败保留整轮旧值 | 先采非零积压，再删数据库记录并让最后查询失败，全部旧 Gauge 保持，未更新为 0 | 通过 |
| 重复注册 | 连续两次 sample，registry meter 总数不变 | 通过 |
| 独立语句边界 | Scheduler Spy 确认查询不处于实际事务中 | 通过 |
| 配置开关与范围 | false 不创建采样器、meter 或线程池；-1/0/4/301 秒拒绝，5/300 秒接受 | 通过 |

测试直接调用 sample()，无 Thread.sleep、无依赖定时器的积压断言。独立进程查询另行验证定时采样。

## Boot 4 OTLP 属性依据

检查本机 `spring-boot-micrometer-metrics-4.1.1.jar!/META-INF/spring-configuration-metadata.json`，sourceType 为 `org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsProperties`，确认：

- `management.otlp.metrics.export.enabled`：框架默认 true，本项目通过 TASKFLOW_OTLP_METRICS_ENABLED 改为默认 false。
- `management.otlp.metrics.export.url`：指标目标 URI。
- `management.otlp.metrics.export.step`：框架默认 1m，本项目明确 60s。

两服务 SpringBootTest 经 Binder 绑定实际 OtlpMetricsProperties，断言 enabled=false、step=60 秒、url 以 /v1/metrics 结尾。单元测试以 `http://collector:4318/v1/traces` 推导为 `http://collector:4318/v1/metrics`，显式 url 覆盖推导值。元数据检查和属性绑定不等于真实 OTLP 接收验收。

静态检查 `rg -n 'Gauge.builder|registry.counter|@Scheduled' taskflow-scheduler/src/main/java/com/taskflow/scheduler/observability taskflow-worker/src/main/java/com/taskflow/worker/observability`，结合回调正文确认只读 AtomicLong/时钟，未调用 Mapper/JdbcTemplate；标签只出现 state 与 outcome 的固定集合。

## 独立进程与真实 curl

使用干净可执行 JAR 和本机 H2 驱动通过 PropertiesLauncher 启动两个独立进程，绑定 127.0.0.1:8081/8082。隔离 YAML 指向各自 H2 内存库，关闭业务扫描、节点上报和 MQ 消费/Relay，仅将采样周期改为 5 秒；未覆盖 Actuator 暴露配置，直接采用 JAR 内 application.yml 的 health,metrics。采样在真实定时线程执行，查询为真实 HTTP。

执行：`.\.tools\metrics-process-default-check.ps1`。实际 curl 命令与输出：

```powershell
curl.exe http://127.0.0.1:8081/actuator/metrics/taskflow.dispatch.outbox.backlog
```

```json
{"availableTags":[{"tag":"state","values":["WAITING","READY","CANCELLED","PUBLISHED","SENDING","FAILED"]}],"measurements":[{"statistic":"VALUE","value":0.0}],"name":"taskflow.dispatch.outbox.backlog"}
```

```powershell
curl.exe http://127.0.0.1:8082/actuator/metrics/taskflow.worker.result.unreported
```

```json
{"availableTags":[],"measurements":[{"statistic":"VALUE","value":0.0}],"name":"taskflow.worker.result.unreported"}
```

两个服务分别查询 `taskflow.metrics.sample?tag=outcome:ok`，实际 COUNT 均为 1.0。两份启动日志均显示 Exposing 2 endpoints，数据源 URL 均为隔离 jdbc:h2:mem:taskflow-metrics-*。脚本 finally 关闭自己启动的两个进程；验收后确认 8081/8082 无监听。

证据：`.tools/metrics-process-clean-check.log`、`.tools/metrics-process-default/{scheduler,worker}.out.log`、`scheduler-curl.json`、`worker-curl.json`、`8081-ok.json`、`8082-ok.json`；隔离 YAML 与脚本均仅保存在忽略的 .tools 中，不提交。

首次使用普通 verify 生成的 Worker JAR 时指标路径返回 404。检查发现 target/classes/application.properties 残留 `management.endpoints.web.exposure.include=health`，而 src/main/resources 已不存在该文件；旧 properties 被打包并覆盖 YAML。显式覆盖暴露列表后 HTTP 成功，但移除覆盖复核仍失败（`.tools/metrics-process-default-check.log`）。随后执行根目录 clean verify，确认旧 properties 已清除，再用默认暴露配置启动，得到上面的实际成功输出。因此本次部署包验收以干净构建为准，不通过修改本机业务 YAML 或源码掩盖历史构建产物问题。

## 未验证范围与定义差异

真实 OTLP collector 接收、后端命名归一化、多 Scheduler 实例口径、真实 MySQL 查询负载及 MQ 故障下指标均未验证；本次无相应环境专项，未将 H2/属性绑定结果充作这些验收。现有业务实例未部署本次代码。无告警、通知、历史存储或持久化审计。

按指标契约，无在线节点的 heartbeat.age=-1，优先于空库测试段落“全部为 0”的笼统表述。心跳用一条 MIN(timestamp) SELECT 返回最早在线心跳，以 Java 做年龄差，属于计数 SQL 形式要求之外的必要时间戳聚合；不改变指标定义。使用可注入 UTC Clock 的单次 instant()，与生产 Instant.now() 等价，便于无等待测试。端点推导通过 EnvironmentPostProcessor 提供低优先级缺省，Boot 属性键名保持不变。Worker 原 pendingCount 包含 RUNNING、reports 有 LIMIT/重试预算/退避过滤，不符合本指标口径，因此新增精确 COUNT 查询。

## 根验证分模块统计

| 模块 | 测试总数 | 通过 | 跳过 | 失败 / 错误 |
| --- | --- | --- | --- | --- |
| Registry | 1 | 1 | 0 | 0 / 0 |
| Config Server | 1 | 1 | 0 | 0 / 0 |
| Gateway | 10 | 9 | 1 | 0 / 0 |
| Admin | 98 | 92 | 6 | 0 / 0 |
| Scheduler | 128 | 128 | 0 | 0 / 0 |
| Worker | 74 | 73 | 1 | 0 / 0 |
| 合计 | 312 | 304 | 8 | 0 / 0 |

8 项跳过：Admin Redis 会话 1、Redis 缓存 3、Redis 连接 1、MySQL 迁移 1；Gateway Redis 限流 1；Worker 真实 Broker 背压 1。均未启用对应环境变量，不计入通过项。统计来自本次根 verify 日志和 Surefire XML。

## 改动文件清单

路径相对仓库根目录，按服务归组。

### Scheduler（5ca8128）

- taskflow-scheduler/src/main/java/com/taskflow/scheduler/dispatch/mapper/DispatchOutboxMapper.java
- taskflow-scheduler/src/main/java/com/taskflow/scheduler/execution/mapper/RunDataMapper.java
- taskflow-scheduler/src/main/java/com/taskflow/scheduler/node/mapper/WorkerNodeMapper.java
- taskflow-scheduler/src/main/java/com/taskflow/scheduler/observability/config/MetricsConfiguration.java
- taskflow-scheduler/src/main/java/com/taskflow/scheduler/observability/config/MetricsProperties.java
- taskflow-scheduler/src/main/java/com/taskflow/scheduler/observability/config/OtlpMetricsEnvironmentPostProcessor.java
- taskflow-scheduler/src/main/java/com/taskflow/scheduler/observability/service/IMetricsSampler.java
- taskflow-scheduler/src/main/java/com/taskflow/scheduler/observability/service/impl/MetricsSamplerImpl.java
- taskflow-scheduler/src/main/resources/META-INF/spring.factories
- taskflow-scheduler/src/main/resources/application.yml
- taskflow-scheduler/src/test/java/com/taskflow/scheduler/observability/DispatchMetricsTests.java
- taskflow-scheduler/src/test/java/com/taskflow/scheduler/observability/MetricsConfigurationTests.java
- taskflow-scheduler/src/test/resources/application-test.yml


### Worker（f02e2c9）

- taskflow-worker/src/main/java/com/taskflow/worker/execution/mapper/ExecutionMapper.java
- taskflow-worker/src/main/java/com/taskflow/worker/observability/config/MetricsConfiguration.java
- taskflow-worker/src/main/java/com/taskflow/worker/observability/config/MetricsProperties.java
- taskflow-worker/src/main/java/com/taskflow/worker/observability/config/OtlpMetricsEnvironmentPostProcessor.java
- taskflow-worker/src/main/java/com/taskflow/worker/observability/service/IMetricsSampler.java
- taskflow-worker/src/main/java/com/taskflow/worker/observability/service/impl/MetricsSamplerImpl.java
- taskflow-worker/src/main/resources/META-INF/spring.factories
- taskflow-worker/src/main/resources/application.yml
- taskflow-worker/src/test/java/com/taskflow/worker/observability/MetricsConfigurationTests.java
- taskflow-worker/src/test/java/com/taskflow/worker/observability/WorkerMetricsTests.java
- taskflow-worker/src/test/resources/application-test.yml

### 文档

- README.md
- docs/development-plan.md
- docs/microservice-platform.md
- docs/reliable-dispatch.md
- docs/observability-metrics.md
- docs/observability-metrics-verification.md

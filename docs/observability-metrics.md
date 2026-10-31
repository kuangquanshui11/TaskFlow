# 积压与延迟指标（S2）

更新：2026-10-31。

## 设计与采样边界

我用 Gauge 表示可增可减的当前积压，Counter 记录累计采样事件；Timer 适合耗时事件，不适合当前堆积。我不在 Gauge 回调里查库，避免抓取与导出线程承担 SQL 延迟或故障。

我将各服务采样编排放在 observability 包，经 IMetricsSampler.sample() 调用本服务既有 Mapper。metricsSampleScheduler 使用独立单线程、metrics-sample- 前缀，初始延迟和完成后固定延迟均取采样周期，不占用默认调度器。测试直接调用 sample()，可控 UTC Clock 推进时间，无须等待定时器。

每轮只读取一次当前 Instant。Scheduler 执行 7 条独立 SELECT：Outbox GROUP BY、5 条 COUNT、在线节点 MIN(last_heartbeat_at)；Worker 执行 2 条 COUNT。心跳在 Java 中用同一观察时间相减，SQL 不计算时间差。各语句独立释放连接，无包围事务、无重试、无写入，查询耗时受已有数据源连接和 Socket 超时约束。sample() 的 Propagation.NEVER 拒绝带事务调用，不借用长事务连接。没有新增表、迁移或索引。

我先收集整轮结果，全部查询成功才更新 AtomicLong、最近成功时间及 ok Counter。任一查询异常保留整轮旧值并递增 failed Counter，等待下一周期；不以 0 冒充未知。AtomicLong 依次更新，抓取可能跨过更新边界，不承诺跨指标原子快照；独立 SELECT 也不构成数据库一致性快照。Gauge 注册一次，只读内存；sample.age 读取内存成功时间戳，与当前时钟相减，故障期间仍增加。

## 指标字典

所有 Gauge 首次成功前为 -1（尚未采样），Counter 初始为 0。成功后无积压才为 0。“保值”表示本轮任一 SQL 失败，整轮保持旧值；从未成功则继续为 -1。计数 Gauge 不设 baseUnit，避免改变后端名字；年龄 Gauge 的 baseUnit 为 seconds。

| 指标名 | 类型 / 单位 | 标签 | 含义、来源 | 失败与负值 |
| --- | --- | --- | --- | --- |
| taskflow.dispatch.outbox.backlog | Gauge / 条 | state：WAITING/READY/SENDING/PUBLISHED/FAILED/CANCELLED | Scheduler dispatch_outbox 按 state 分组，缺失状态补 0 | 保值；首次 -1 |
| taskflow.dispatch.outbox.publish.overdue | Gauge / 条 | 无 | dispatch_outbox：READY 且 next_publish_at<=now | 保值；首次 -1 |
| taskflow.dispatch.outbox.claim.expired | Gauge / 条 | 无 | dispatch_outbox：SENDING 且 claim_until<=now | 保值；首次 -1 |
| taskflow.dispatch.attempt.dispatch.overdue | Gauge / 条 | 无 | run_attempt：READY/DISPATCHING 且 next_dispatch_at<=now | 保值；首次 -1 |
| taskflow.dispatch.attempt.lease.expired | Gauge / 条 | 无 | run_attempt：READY/DISPATCHING/RUNNING 且 expires_at<=now | 保值；首次 -1 |
| taskflow.dispatch.attempt.lease.expiring | Gauge / 条 | 无 | run_attempt：上述非终态，expires_at 在 (now,now+60s] | 保值；首次 -1 |
| taskflow.worker.result.unreported | Gauge / 条 | 无 | Worker worker_execution：reported=FALSE 且 SUCCEEDED/FAILED，含重报耗尽和退避中的记录 | 保值；首次 -1 |
| taskflow.worker.execution.backlog | Gauge / 条 | 无 | worker_execution：ACCEPTED；不计 RUNNING | 保值；首次 -1 |
| taskflow.worker.node.heartbeat.age | Gauge / 秒 | 无 | Scheduler worker_node：expires_at>now 的在线节点中最大 now-last_heartbeat_at | 保值；首次或无在线节点为 -1 |
| taskflow.metrics.sample.age | Gauge / 秒 | 无 | 两服务各自内存：距最近成功采样观察时间的秒数 | 失败后持续增加；从未成功为 -1 |
| taskflow.metrics.sample | Counter / 次 | outcome：ok/failed | 两服务各自采样轮次，一轮仅计入一种结果 | 失败递增 failed，无负值 |

Attempt lease 指标严格使用 expires_at，短执行租约 lease_until 的业务回收规则保持独立。未来心跳时间戳的差值防御性钳制为 0；时钟回拨时 sample.age 同样不显示负年龄。指标不能证明 Handler 实际进度。

我只允许 state 与 outcome 的固定枚举作为本能力的 meter 标签，不添加 attemptId、runId、workerId 或队列名。新增指标须先明确服务数据归属、单条只读查询、单位、负值与失败语义、有限标签全集、查询成本及注入式测试，不得在 Gauge 回调查库。

## 三个独立周期与配置

| 配置 | 默认 | 覆盖与边界 |
| --- | --- | --- |
| taskflow.metrics.enabled | true | TASKFLOW_METRICS_ENABLED；false 不创建本能力的采样器、线程池和 meter，既有业务指标保留 |
| taskflow.metrics.sample-interval | 15s | TASKFLOW_METRICS_SAMPLE_INTERVAL；5s–5m，含端点；非法值拒绝启动 |
| management.otlp.metrics.export.enabled | false | TASKFLOW_OTLP_METRICS_ENABLED；与本地采样开关独立 |
| management.otlp.metrics.export.step | 60s | 标准 Spring 属性覆盖；导出频率独立于采样 |
| management.otlp.metrics.export.url | 从 traces 推导 | TASKFLOW_OTLP_ENDPOINT 默认 http://localhost:4318/v1/traces，替换 /v1/traces 为 /v1/metrics；显式标准 url 配置优先 |

采样周期决定数据库观察频率，导出周期决定发送频率；未来告警评估周期属于 S3，当前未实现，不能由前两者推断告警时效。配置更新默认重启生效，独立线程不代表 SQL 无成本或故障查询不会延长一轮时间。

我以本机 spring-boot-micrometer-metrics-4.1.1.jar 的 META-INF/spring-configuration-metadata.json 核实 enabled/url/step，sourceType 为 OtlpMetricsProperties，step 缺省 1m。starter-opentelemetry 已传递引入 micrometer-registry-otlp，无新增依赖。各服务的 EnvironmentPostProcessor 在 ConfigData 后提供低优先级缺省 url，避免在 YAML 中使用不能执行的字符串替换表达式；绑定测试验证 Boot 的实际属性对象。自定义 traces 路径不含 /v1/traces 时应显式指定指标 url。

## 本地查询与名称转换

Scheduler/Worker Actuator 暴露 health,metrics，默认只绑定 127.0.0.1。Gateway 不转发 /actuator，Nginx 不代理，因此默认不是对外暴露；Admin 保持只有 health。部署覆盖绑定地址时，由部署方管理其访问边界。源码曾删除或改名配置文件的工作区应先执行 `.\mvnw.cmd clean verify` 再部署，避免 target 残留的旧 application.properties 覆盖当前 YAML，见本次验证记录。

```powershell
curl.exe http://127.0.0.1:8081/actuator/metrics/taskflow.dispatch.outbox.backlog
curl.exe 'http://127.0.0.1:8081/actuator/metrics/taskflow.dispatch.outbox.backlog?tag=state:WAITING'
curl.exe http://127.0.0.1:8081/actuator/metrics/taskflow.worker.node.heartbeat.age
curl.exe http://127.0.0.1:8082/actuator/metrics/taskflow.worker.result.unreported
curl.exe 'http://127.0.0.1:8082/actuator/metrics/taskflow.metrics.sample?tag=outcome:failed'
curl.exe http://127.0.0.1:8082/actuator/metrics/taskflow.metrics.sample.age
```

未过滤 state 时 Actuator 可能聚合多态值，单态使用 tag 查询，尤其首次未知时。Micrometer 保留点分隔名，OTLP 后端的 Prometheus 转换示例：

- `taskflow.dispatch.outbox.backlog{state="WAITING"}` → `taskflow_dispatch_outbox_backlog{state="WAITING"}`
- `taskflow.metrics.sample{outcome="failed"}` → `taskflow_metrics_sample_total{outcome="failed"}`
- 秒单位的 age 可能由接收端添加 `_seconds` 后缀。

实际接收端可以有自己的归一化策略。这些示例不代表新增 Prometheus 端点；当前没有 /actuator/prometheus、看板或 Prometheus/Grafana 部署。

## 数据口径

我只观察当前进程连接的数据库。多实例 Scheduler 各自采样，共享数据库时通常观察相同积压，相加会重复计算；不能把一个实例的数字当作集群总量。Worker 自有 Inbox 也不是 Scheduler 的权威状态。

指标不是审计，不持久化、不历史回放，不保证与导出时刻一致。当前无告警、通知或阈值判定，持续积压只作排查线索。证据及未验证范围见 [本次验证](observability-metrics-verification.md)。

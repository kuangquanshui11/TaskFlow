# TaskFlow 业务失败与多节点告警

我沿用 Scheduler 和 Worker 的 Micrometer OTLP 指标，由 Prometheus 评估告警，Alertmanager 分组后发送 Webhook。Scheduler 从自己的数据库观察终态 Run、Worker 心跳、派发 Outbox 和已入库死信。Worker 从自己的 Inbox 观察本节点的结果上报耗尽。Broker 死信队列通过 RabbitMQ 自带的 Prometheus 插件采集。[指标字典](observability-metrics.md) · [派发故障查询与处置](api.md#派发故障查询与处置)

## 规则与数据边界

| 告警 | 触发与恢复 | 定位 |
| --- | --- | --- |
| `TaskFlowRunBusinessFailure` | 最近 5 分钟按 `finished_at` 观察到 `FAILED` 且结果码为 `BUSINESS_HANDLER_FAILED` 或 `HANDLER_FAILED`。按结果码分别触发，窗口清空后解除。进入 `RETRY_WAIT` 的 Attempt 不算终态 | 通知包含结果码和运行列表链接，默认查最近 24 小时的结束时间。可根据通知的触发时间缩小范围，点击 Run ID 查看任务快照和 Attempt |
| `TaskFlowWorkerOffline` | 预期 Worker 没有注册记录或心跳过期持续 1 分钟。注册或心跳恢复后解除 | 通知包含 `worker_id`。在控制台「工作节点」按 ID 搜索，在部署清单核对从未注册的节点 |
| `TaskFlowSchedulerDispatchOverdue` | 到期尚待派发的 Attempt 持续存在 2 分钟 | 核对 Worker 资格、派发 Outbox 和 Broker |
| `TaskFlowDispatchOutboxPublishOverdue` / `TaskFlowDispatchOutboxClaimExpired` / `TaskFlowDispatchOutboxExhausted` | 分别观察 READY 发布逾期、SENDING 领取超时、发布耗尽且未结案，持续 2 分钟 | 根据类型核对派发日志、Broker 与运行详情的派发故障 |
| `TaskFlowDeadLetterUnresolved` | 已入库死信持续有记录待结案，持续 2 分钟 | 在运行详情或派发故障查询中用 Run、Attempt 或事件 ID 检索 |
| `TaskFlowWorkerReportExhausted` | 某 Worker Inbox 存在结果上报耗尽且尚未确认，持续 2 分钟 | 标签 `instance` 是 Worker ID。用该节点报告日志中的 Run / Attempt ID 查询运行详情。Scheduler 结案和 Worker 本地结果确认各自独立 |
| `TaskFlowBrokerDeadLetterBacklog` | Broker 死信队列深度（待消费及未确认）持续大于零满 2 分钟 | 通知包含 `broker_cluster`、`vhost` 和 `queue` |
| `TaskFlowSchedulerMetricsUnavailable` / `TaskFlowWorkerMetricsUnavailable` | 预期实例尚无有效采样、样本陈旧或指标缺失，持续 2 分钟。该实例恢复有效样本后解除 | 用 `job` 和 `instance` 核对本地采样日志、Actuator 与 OTLP 配置 |
| `TaskFlowSchedulerClusterMetricsUnavailable` / `TaskFlowBrokerMetricsUnavailable` | 无新鲜 Scheduler 样本或某 Broker 目标抓取失败，持续 2 分钟。任一 Scheduler 恢复有效采样或对应 Broker 抓取重新成功时解除 | 核对 Scheduler 数据源、采样日志或 Broker 抓取状态 |

应用每 15 秒采样并导出，Prometheus 每 15 秒评估。最近成功的采样年龄加上导出以后经过的时间，最多为 90 秒。参与业务规则的计数也必须非负且最近 90 秒内导出。采样 SQL 失败时保留旧值，直到该轮数据过期。此时相关业务规则退出，收到 resolved 表示规则退出，确认实际恢复需要看到新鲜样本及清零。所有 Scheduler 样本过期时，由集群采样中断告警表示业务状态未知。终态业务失败规则观察滚动窗口，采集停顿超过窗口时可从运行历史补查。

我让读取同一 Scheduler 数据库的实例各自采样，过滤陈旧来源后取最大值。Outbox、Attempt、Run 和入库死信各自按指标语义聚合，同一记录只进入一条集群告警。分时观测时，较大的新鲜读数可能维持到下一次采样。Worker 在线只要其中一台有效 Scheduler 样本显示在线即可。每个 Worker 的 Inbox 独立，按对应 Worker ID 告警。Broker 只采纳最新抓取成功的来源，同一 `broker_cluster/vhost/queue` 取最大值，队列在节点间迁移时告警身份保持一致。抓取失败后业务样本失效，告警应与抓取状态一起排查。

## 部署与排查

1. 准备 Docker Compose v2、Scheduler / Worker 和 Alertmanager 可访问的通用 JSON Webhook。接收端返回 2xx。将实际 URL 保存到被忽略的 UTF-8 文件中，例如 `.tools/scheduler-alert-webhook.txt`。
2. 按部署调整 `deploy/observability/prometheus.yml` 的 `environment` 与控制台绝对地址 `console_url`，末尾省略斜杠。每套 Prometheus 配置只监控一个共享 Scheduler 数据库的部署环境。按 `expected-targets.yml` 的格式列出预期的 Scheduler 和 Worker。每个 Scheduler 分配唯一的 `TASKFLOW_SCHEDULER_METRICS_INSTANCE_ID`。Worker 的 OTLP `instance` 直接沿用 `TASKFLOW_WORKER_ID`。计划退役时移除对应预期记录。
3. 将 `config/taskflow-scheduler-alerts.example.yml` 和 `config/taskflow-worker-alerts.example.yml` 分别合并到被忽略的 Scheduler、Worker 本地配置。所有读取同一库的 Scheduler 使用同一个 `TASKFLOW_METRICS_EXPECTED_WORKERS`，以逗号分隔，最多 100 个 Worker ID。更新节点清单时同时更新这份配置和 Prometheus 的预期目标。重启相应应用使配置生效。跨主机 OTLP 导出指向私有监控网络上的接收入口。
4. 需要采集 Broker 时启用 `rabbitmq_prometheus`，在 `rabbitmq.targets.yml` 添加每个集群的节点和集群标识。多节点使用同一个集群标签。若 Broker 与 Prometheus 位于不同 Compose 项目，需要给 Prometheus 接入 Broker 的私有网络或填写可从监控容器路由的地址。根据部署调整 `prometheus.yml` 中的 vhost 过滤。留空的目标文件表示未启用 Broker 采集。

   ```yaml
   - targets: ["rabbit1:15692", "rabbit2:15692", "rabbit3:15692"]
     labels:
       broker_cluster: taskflow-mq
   ```

5. 启动监控组件。Prometheus 和 Alertmanager 对主机只发布回环端口，Webhook URL 通过只读 secret 挂载。

   ```powershell
   $env:TASKFLOW_ALERT_WEBHOOK_FILE = (Resolve-Path .tools/scheduler-alert-webhook.txt).Path
   docker compose -f deploy/observability/compose.yml config --quiet
   docker compose -f deploy/observability/compose.yml up -d
   ```

6. 在 `http://127.0.0.1:9090/alerts` 和两个 Alertmanager 页面（端口 9093 和 9095）核对规则、集群成员和告警。Prometheus 查询可用 `taskflow:metrics:expected`、`taskflow:scheduler_metrics:fresh`、`taskflow:worker_metrics:fresh`、`taskflow:worker_node:online` 和 `taskflow:broker_dead_letter:queued`。本机 Actuator 采样正常但 Prometheus 无样本时，检查导出地址、资源标识、进程时钟和导出日志。已触发但未通知时，检查两台 Alertmanager 的日志、secret 挂载及 Webhook 响应。

### 通知高可用

我将 Prometheus 配置为同时把告警发送给两个 Alertmanager。两个成员配置相同接收端，使用各自的数据卷，通过 9094 TCP/UDP 同步静默和通知记录。Compose 将两者部署在同一主机，用于验证进程级故障切换。生产若要覆盖主机故障，分别部署在不同主机，使用对端可达的通告地址，并让 Prometheus 直接访问两台的 HTTP 接口。Prometheus 和 Webhook 接收端各自的可用性由部署环境管理。网络分区期间可能出现重复通知，接收端要允许重复投递。[Alertmanager 高可用](https://prometheus.io/docs/alerting/latest/high_availability/)

通知按环境和告警名分组。首批等待 10 秒，同组变化间隔 30 秒，持续告警 4 小时后重复通知。节点、结果码或队列标识保留在每条告警中，通知发送耗时还受规则评估、聚类同步和 Webhook 响应影响。停用时先关闭应用的 OTLP 指标导出并重启，再停止监控 Compose。数据卷保留历史指标与通知记录。

## 验证命令

规则和通知验收使用与 Compose 相同版本的 Prometheus 3.13.5、Alertmanager 0.34.2。端到端脚本还需要 Java 21、Node.js 和 Maven 缓存中的 H2 驱动。[官方下载](https://prometheus.io/download/)

```powershell
.\mvnw.cmd -B -o verify
promtool check config deploy/observability/prometheus.yml
promtool test rules deploy/observability/scheduler-alerts.test.yml
amtool check-config deploy/observability/alertmanager.yml
.\tools\test-scheduler-alerts.ps1 -PrometheusPath (Get-Command prometheus.exe).Source `
  -AlertmanagerPath (Get-Command alertmanager.exe).Source -SkipBuild
```

上线前确认 Scheduler 数据库已应用 Flyway V13。该迁移给 `run_execution.finished_at` 新增索引，用于最近终态失败采样和结束时间查询。

端到端脚本通过两个 Scheduler、一个 Worker、两个 Alertmanager、Prometheus 和本机 Webhook 验证告警触发、分组、恢复和单成员停止后的通知。Scheduler 共用一个隔离 H2 库，Worker 使用独立 H2。脚本用两个 HTTP 指标夹具模拟同一死信队列的重复观测和来源切换。实际 RabbitMQ quorum 队列切换、Compose 容器及线上 Webhook 需在目标环境独立验收。脚本证据保存到 `.tools/scheduler-alerts/`。

### RabbitMQ 领导者切换验收

在隔离 RabbitMQ 4.3+ 三节点集群建立临时 vhost 和三副本 quorum 死信队列。Prometheus 抓取三台节点并使用同一个 `broker_cluster` 标签。保留一条待消费死信，核对告警只对应一个 `broker_cluster/vhost/queue`。记录队列领导者，停止该隔离节点，确认接任节点可被抓取、消息仍在、队列告警身份未变化。恢复原节点并消费测试消息，再核对一条队列恢复通知。保存切换前后的 `rabbitmq-queues quorum_status`、Prometheus 查询和 Webhook 通知。

### 2026-11-16 多节点验证记录

环境为 Windows、Java 21.0.12.1、Maven 3.10.0、H2 2.4.240、MySQL 8.0.34、Prometheus 3.13.5、Alertmanager 0.34.2、Docker Compose 5.6.1 和 Chromium。证据时间使用 Asia/Shanghai。

| 命令或检查 | 结果 | 证据 |
| --- | --- | --- |
| Maven `-B -o --fail-at-end verify` | 全仓最终通过，402 项通过、52 项按条件跳过。Windows Wrapper 启动失败后使用其缓存的 Maven 3.10.0，在隔离源码副本中复跑。前两次全仓验证分别遇到 Admin 配置对账和订单清理测试失败，最终独立运行均通过 | `.tools/p04-root-final.log` |
| `promtool check config`、`promtool test rules`、`amtool check-config`、Compose `config --quiet` | 配置全部通过，6 组规则用例通过 | `deploy/observability/scheduler-alerts.test.yml` |
| `test-scheduler-alerts.ps1 -SkipBuild` | 19 项检查通过，11:16:11 完成。13 组触发通知和 13 组恢复通知，每组一次。覆盖双 Scheduler、Worker 离线和恢复、旧积压值失效以及停止一台 Alertmanager 后的通知 | `.tools/scheduler-alerts/20261116-110306-176/report.json` 和同目录 `webhook.jsonl` |
| `test-execution.ps1 -SkipBuild -IncludeRunRerun` | 53 项隔离检查、30 项前端单元测试、3 项 Playwright 用例和前端构建通过。查看了结束时间筛选桌面截图和 390px 移动端截图 | `.tools/execution-e2e/20261116-110907-819/report.json`、`global-runs-finished.png` 和 `global-runs-mobile.png` |
| 隔离 MySQL 执行 Scheduler V1–V13，用 1,000 条终态 Run 检查 `EXPLAIN` | 近 5 分钟匹配 4 条。失败采样和结束时间查询均以 `range` 使用 `idx_run_finished_at` | `.tools/p04-mysql/20261116-110204/result.log` |

Broker 故障和重复来源用 HTTP 指标夹具验证。实际 RabbitMQ 4.3+ quorum 领导者切换、Compose 容器启动和目标环境 Webhook 联调待实地验收。

### 2026-11-09 单 Scheduler 验证记录

环境为 Windows、Java 21.0.12.1、Node.js 24.15.0、H2 2.4.240、Prometheus 3.13.5 和 Alertmanager 0.34.2。隔离 Scheduler 每 5 秒采样，导出、规则评估和通知时序沿用部署配置。

| 验证 | 结果 |
| --- | --- |
| 根目录 `mvnw.cmd -B -o verify` | 376 项中 352 项通过、24 项条件跳过，0 项失败或错误。日志 `.tools/scheduler-alerts-root-verify.log` |
| `promtool check config` 和 `promtool test rules` | 配置有效，5 组规则用例通过，覆盖短时尖峰、持续逾期、未知值、缺失序列、停止导出和旧值保留 |
| `amtool check-config` 和 Compose `config --quiet` | 回环 webhook 验收配置和 Compose v5.6.1 部署文件均通过语法校验 |
| `test-scheduler-alerts.ps1 -SkipBuild` | 10 项端到端检查通过，于 10:19:17（Asia/Shanghai）完成。两条告警各收到一次触发和一次恢复通知 |

证据位于 `.tools/scheduler-alerts/20261109-101114-105/`。`report.json` 保存检查和时间，`webhook.jsonl` 保存四条通知，`dispatch-firing.json` 与 `metrics-firing.json` 保存对应的 Prometheus 状态。采样失败期间，逾期计数保留为 1，只有采样中断规则触发。采样恢复后计数为 0，活动告警为空。

本轮覆盖单 Scheduler、隔离 H2 和回环 webhook。Compose 容器启动、实际 webhook 接收端、MySQL 故障和多实例部署仍待独立验收。

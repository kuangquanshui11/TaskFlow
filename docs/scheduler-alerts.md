# Scheduler 告警 S3

我用 Scheduler 已有的 Micrometer OTLP 导出，将派发逾期数量和最近成功采样年龄发送到 Prometheus。Prometheus 评估规则，Alertmanager 按告警名和实例分组，通过已配置的 webhook 发送触发和恢复通知。Scheduler 继续读取自己的数据库，告警链路只消费导出的指标。

## 规则与数据边界

| 告警 | 触发条件 | 解除条件 |
| --- | --- | --- |
| `TaskFlowSchedulerDispatchOverdue` | 指标新鲜且派发逾期数量持续大于 0，满 2 分钟 | 逾期数归零或指标失去新鲜度 |
| `TaskFlowSchedulerMetricsUnavailable` | 指标缺失、尚未采样或过期，持续 2 分钟 | 两项指标恢复有效且新鲜 |

我在 `taskflow:scheduler_metrics:fresh` 中检查两个 Gauge 均存在且非负。最近成功采样年龄加上本次导出距今的时间须不超过 90 秒，逾期数量的导出也须在最近 90 秒内。采样失败时 Gauge 会保留原来的数量，即使逾期数量仍为正，也会随采样年龄超过 90 秒而退出派发告警。停止 OTLP 推送时，Prometheus 同样按上次接收的指标时间戳判定过期。没有序列的启动窗口使用 2 分钟等待期。

部署示例每 15 秒采样、导出和评估一次。阈值与持续时间在 `deploy/observability/scheduler-alerts.yml` 中配置，修改后重启 Prometheus。Alertmanager 首次分组等待 10 秒，同组更新间隔 30 秒，持续触发时每 4 小时重复通知。实际通知时间还取决于各周期对齐和接收端可用性。

我为首版指定一个告警来源，OTLP 资源标识为 `service.name=taskflow-scheduler` 和 `service.instance.id=scheduler-1`。Prometheus 转为 `job` 和 `instance`，名称转换保留单位后缀，例如 `taskflow_metrics_sample_age_seconds`。其他实例的积压不累加。多实例部署中，应只给承担告警采样的一个 Scheduler 使用 `scheduler-1`，迁移该角色时先停旧实例的导出。多实例逐节点健康及通知高可用需独立实施。 [Prometheus OTLP 指南](https://prometheus.io/docs/guides/opentelemetry/)

## 部署与排查

1. 准备 Docker Compose v2、可运行的 Scheduler 以及 Alertmanager 容器可访问的 webhook 接收端。接收端应处理 Alertmanager 通用 JSON 并返回 2xx。将实际 URL 单独保存到本地忽略目录下的 UTF-8 文本文件，例如 `.tools/scheduler-alert-webhook.txt`。
2. 启动监控组件。Compose 以只读 secret 提供 URL，两个 HTTP 端口只发布到主机回环地址。

   ```powershell
   $env:TASKFLOW_ALERT_WEBHOOK_FILE = (Resolve-Path .tools/scheduler-alert-webhook.txt).Path
   docker compose -f deploy/observability/compose.yml config --quiet
   docker compose -f deploy/observability/compose.yml up -d
   ```

3. 将 `config/taskflow-scheduler-alerts.example.yml` 中的设置合并到被忽略的 `config/taskflow-scheduler-local.yml`。已有 `management` 键时合并子项。Scheduler 默认每 15 秒采样。若采样已关闭，先启用 `taskflow.metrics.enabled`。独立主机部署须配置 Scheduler 可访问的 `TASKFLOW_SCHEDULER_ALERTS_OTLP_URL`，使用受限监控网络和认证入口。重启 Scheduler 后生效。
4. 打开 `http://127.0.0.1:9090/alerts`，在 Prometheus 查询 `taskflow:scheduler_metrics:fresh{job="taskflow-scheduler",instance="scheduler-1"}`。正常情况下返回 1。`http://127.0.0.1:9093` 展示 Alertmanager 收到的告警。

收到派发逾期告警时，先检查 Worker 资格、Scheduler Outbox 状态及 Broker 连接。收到采样中断告警时，检查 Scheduler 采样日志和 `/actuator/metrics/taskflow.metrics.sample.age`。若本地样本新鲜但 Prometheus 缺少序列，检查 OTLP 地址、导出错误、时钟和资源标识。若规则已触发但 webhook 没有收到通知，检查 Alertmanager 容器日志、URL 文件挂载和接收端响应。 [Alertmanager webhook 配置](https://prometheus.io/docs/alerting/latest/configuration/#webhook_config)

停用时先关闭指定 Scheduler 的 OTLP 指标导出并重启，再停止监控 Compose。Compose 数据卷保留最近的指标及 Alertmanager 状态。

## 验证命令

规则测试使用与 Compose 相同版本的 Prometheus 3.13.5。通知验收同时使用 Alertmanager 0.34.2、Java 21、Node.js 和 Maven 本机仓库中的 H2 驱动。Windows 可从官方发布页下载工具，按发布页的 SHA256 校验，并将 `promtool.exe`、`prometheus.exe` 和 `alertmanager.exe` 所在目录加入 PATH。 [官方下载](https://prometheus.io/download/)

```powershell
.\mvnw.cmd -B -o verify
promtool check config deploy/observability/prometheus.yml
promtool test rules deploy/observability/scheduler-alerts.test.yml
.\tools\test-scheduler-alerts.ps1 -PrometheusPath (Get-Command prometheus.exe).Source `
  -AlertmanagerPath (Get-Command alertmanager.exe).Source -SkipBuild
```

端到端脚本使用真实 Scheduler 可执行 JAR、独立 H2 文件库、原生 Prometheus 和 Alertmanager。它保留发布规则及通知计时，在随机回环端口启动测试 webhook。脚本通过 Scheduler 内部 API 创建 Attempt，再在隔离库调整到期时间和暂时重命名 `worker_node`，验证 SQL 采样失败时保留旧值。报告记录告警 API 和 webhook 的触发、恢复结果，保存到 `.tools/scheduler-alerts/`。脚本退出时清理本轮进程。

### 2026-11-09 验证记录

环境为 Windows、Java 21.0.12.1、Node.js 24.15.0、H2 2.4.240、Prometheus 3.13.5 和 Alertmanager 0.34.2。隔离 Scheduler 每 5 秒采样，导出、规则评估和通知时序沿用部署配置。

| 验证 | 结果 |
| --- | --- |
| 根目录 `mvnw.cmd -B -o verify` | 376 项中 352 项通过、24 项条件跳过，0 项失败或错误。日志 `.tools/scheduler-alerts-root-verify.log` |
| `promtool check config` 和 `promtool test rules` | 配置有效，5 组规则用例通过，覆盖短时尖峰、持续逾期、未知值、缺失序列、停止导出和旧值保留 |
| `amtool check-config` 和 Compose `config --quiet` | 回环 webhook 验收配置和 Compose v5.6.1 部署文件均通过语法校验 |
| `test-scheduler-alerts.ps1 -SkipBuild` | 10 项端到端检查通过，于 10:19:17（Asia/Shanghai）完成。两条告警各收到一次触发和一次恢复通知 |

证据位于 `.tools/scheduler-alerts/20261109-101114-105/`。`report.json` 保存检查和时间，`webhook.jsonl` 保存四条通知，`dispatch-firing.json` 与 `metrics-firing.json` 保存对应的 Prometheus 状态。采样失败期间，逾期计数保留为 1，只有采样中断规则触发。采样恢复后计数为 0，活动告警为空。

本轮覆盖单 Scheduler、隔离 H2 和回环 webhook。Compose 容器启动、实际 webhook 接收端、MySQL 故障和多实例部署仍待独立验收。

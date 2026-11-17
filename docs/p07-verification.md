# P07 执行控制验证

2026 年 11 月 17 日在 Windows、Java 21 和 Maven 3.10.0 下执行。服务集成使用独立的 H2 或 MySQL 测试库。控制台交互使用无头 Chrome 和隔离组件页面。

| 范围 | 命令或证据 | 结果 |
| --- | --- | --- |
| 根目录构建与测试 | `.\mvnw.cmd -B verify`，`.tools/p07-full-verify.log` | 全部模块通过 |
| 指数退避 | `.tools/p07-retry-tests.log` | 103 项通过，覆盖固定间隔兼容、退避与抖动上限、尝试次数上限、旧请求重放 |
| 并发与取消 | `.\tools\test-run-state-mysql.ps1 -IncludeDispatchFairness -IncludeExecutionLease`，`.tools/run-state-mysql/20261117-095433-871/tests.log` | MySQL 8.0.34 上 45 项通过，覆盖双扫描器竞争、新旧配置共存、重试保留占位、取消与正常结果竞争、迟到结果和人工核对 |
| 管理台 | `npm.cmd test`、`npm.cmd run build`，`.tools/p07-cancel-web.log` | 单元测试 31 项通过，生产构建通过。Chrome 验证了退避和串行设置、核对表单、取消原因校验及同键重试 |
| 真实 RabbitMQ | `.\tools\test-mq.ps1 -SkipBuild -SkipBrowser -SkipFailover -IncludeRunCancellation -AllowLegacyBroker -ClusterSettingsPath .tools/mq-cluster-settings.json`，`.tools/p07-mq-retry.log` | 三节点 quorum 队列验证运行中取消及发布后才送达的取消 |

真实 MQ 用例在随机测试 vhost 内执行。运行中的 Worker 通过续租收到取消并退出，Scheduler 收到停止报告后保留一个 Attempt 并生成待核对记录。另一条消息在取消前已由 RabbitMQ 确认，Worker 重启后把消息写入 Inbox，Scheduler 拒绝该 Attempt 的执行租约，时间线记录迟到上报被拒绝。该轮的服务证据在 `.tools/execution-e2e/20261117-101452-952`，Broker 证据在 `.tools/mq-e2e/taskflow_it_2cdd6cdba5f746fab5909cd890ff66c5`。

本轮 Broker 为 RabbitMQ 3.8.34，范围为正常三节点拓扑和 Worker 重启。Broker 节点切换、至少一次死信转移以及同一 Attempt 跨 Inbox 接管仍按各自专项验收。

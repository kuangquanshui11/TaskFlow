# 带登录的安全与任务链路验证

日期：2026-10-30。

## 环境和命令

Windows、PowerShell 7.6.5、Java 21.0.12.1、Node 24.15.0、Chrome / Playwright 1.64.1、Nginx 1.31.8、Keycloak 26.8.1、Redis 7.4.8 Windows MSYS2 发行包。Gateway、Admin、Scheduler、Worker 使用当前构建的独立可执行 JAR；三个业务服务分别使用独立 H2 文件库。身份服务使用本轮随机 Realm 与独立 H2 文件库，Admin 会话及 Gateway 限流分别使用本轮独立 Redis 进程。RabbitMQ 为外部三节点 3.8.34 集群，仅使用本轮随机 vhost。

```powershell
.\mvnw.cmd verify
.\tools\test-mq.ps1 -SkipBuild -SkipFailover -ClusterSettingsPath .tools/mq-cluster-settings.json -AllowLegacyBroker
```

## 结果与证据

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 根目录 Maven verify | 通过；263 项，255 项执行通过，8 项 opt-in 跳过；六个应用构建成功 | `.tools/verification/access-execution-verify.log` |
| 三节点 quorum 拓扑 | 通过；两条队列均有三个成员和三个在线副本 | MQ 证据目录 `quorum-before.json` |
| 真实 Broker 满载背压 | 1 项执行通过，未跳过 | MQ 证据目录 `backpressure.log`、`report.json` |
| 任务与安全链路汇总 | 53 项通过 | 执行证据目录 `report.json` |
| JWT、权限、真实 Redis 会话、轮换、退出和 TTL 到期 | 38 项通过 | `security-security.json` |
| Gateway 限流 Redis 故障 | 4 项通过；503 / RATE_LIMIT_UNAVAILABLE，直连 Admin 仍可读，请求标识查询无 Run | `security-rate-redis.json` |
| Admin 会话 Redis 故障 | 3 项通过；Admin 直连 503 / SESSION_UNAVAILABLE，Gateway 也拒绝创建 Run | `security-session-redis.json` |
| 两类 Redis 故障后的任务恢复 | 通过；有效 Job 的故障请求未新增 Scheduler Run；恢复后显式创建新请求并经 MQ 执行成功 | 执行证据目录 `report.json` |
| 前端单元测试和生产构建 | 12 项通过；Vite 构建成功 | `.tools/verification/authenticated-mq-regression-final.log` |
| Nginx 正式模板配置检查 | 通过 | 同上 |
| 带登录的控制台浏览器回归 | 17 项通过 | 同上、`taskflow-web/playwright-report` |
| 真实登录、只读权限、退出及 Redis 会话过期浏览器回归 | 3 项通过 | 同上；本轮执行目录中的登录、登录后及 VIEWER 截图 |
| 进程清理 | 通过；脚本返回 0，测试端口不再监听 | 19080/19081/19082/19088/8091/19190/16379/16380 TCP 检查 |

执行证据目录：`.tools/execution-e2e/20261030-175547-152/`。

MQ 证据目录：`.tools/mq-e2e/taskflow_it_3cb4043248bd40aa98ca069af01ae0a6/`。该目录的 `report.json` 记录 `capacity-backpressure` 和 `three-nodes` 均为 PASS，并关联上述执行目录。成功后本轮测试 vhost 已按脚本清理，服务日志、报告和截图保留。账号、密码、JWT、刷新令牌及数据库文件均只保留在忽略目录，未纳入版本管理。

完整链路实际经过交互式 Keycloak PKCE → Gateway JWT 与 Redis 限流 → Admin JWT 与 Redis 会话 → 创建 Job → Outbox 配置同步 → Scheduler Cron / 手动 Run → RabbitMQ → Worker → 结果上报 → 带 JWT 查询。未关闭安全链，也未加载内存会话替身。所有浏览器 E2E API 请求随页面重载使用当前 JWT，退出检查等待身份服务跳转完成后才关闭浏览器；失败 POST 不自动重放。

## 适用边界

本轮使用真实 Redis Lua 和 TTL；保持应用的 30 分钟闲置配置，并观察 TTL 创建和续期。到期测试只加速一个已知测试会话的 Redis TTL，测试 login-window 为 5 秒，未实际闲置等待 30 分钟。

业务数据库使用 H2，并非生产 MySQL 验收；生产 TLS、实际业务身份 Realm、Eureka / Config Server / OTLP 组合、本轮未选择的 Worker 集群及租约可选分支未重新验证。`-SkipFailover` 明确跳过 MQ 节点故障切换。RabbitMQ 3.8.34 的死信策略记录为 at-most-once，本轮不验收可靠死信转移、多数派丢失或生产集群故障保证。此前验证记录仍保留各自日期和范围。

运行与失败处理见 [带登录的任务链路回归](authenticated-regression.md)。

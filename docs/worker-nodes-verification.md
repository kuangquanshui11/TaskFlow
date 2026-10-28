# Worker 节点模块验证记录

验证日期：2026-10-27。范围为节点注册、心跳、会话隔离、在线状态、固定目标派发检查及 Admin/Vue 节点查询。协议见 [节点管理](worker-nodes.md)。

## 后端与构建

根目录 `mvnw.cmd verify` 最终通过：141 项测试中 136 项通过、5 项按外部环境条件跳过，0 失败、0 错误，三个可执行 JAR 生成。5 项跳过仍为真实 Redis 连接 1 项、真实 Redis 缓存 3 项和 Admin 专用 MySQL 迁移 1 项，不计入通过项。

新增 18 项测试：

- Scheduler WorkerNodeTests 9 项：节点独立身份验证、参数校验、注册幂等、精确离线边界、心跳重复/乱序、活跃身份占用、旧会话隔离、事务回滚、分页统计、队列归属、HTTP 派发检查和数据库时间精度。
- Scheduler WorkerNodeMqGatingTests 1 项：错误 MQ 路由阻止准备，节点过期阻止 Relay 发布，不消耗发布次数，恢复心跳后发布原事件。
- Admin WorkerNodeApiTests 5 项：真实 HTTP Client、SQL 事务外查询、过滤编码、400/404/503、错误/不一致响应检查和详情身份检查。
- Worker WorkerRegistrationTests 3 项：实际能力与负载上报、递增序号、注册超时后复用原 session、记录缺失后原 session 重注册，不绕过会话隔离。

Flyway 在隔离 H2 从空库应用到 V6，既有 V2/V4 升级测试也应用最新迁移并通过。V6 的真实 MySQL 升级尚未执行，不声称业务 MySQL 已升级。

回归发现派发时间有纳秒而数据库为 TIMESTAMP(3)，向上舍入可能令“立即就绪”的 Attempt/Outbox 暂时落在未来。RunDataMapper 与 DispatchOutboxMapper 现在在 SQL 边界统一截断到毫秒，新增 999999ns 边界用例，保证在同一持久化毫秒内即可扫描到；没有放宽状态、租约或心跳有效期检查。

## 三个真实进程

执行 `tools/test-execution.ps1 -SkipBuild`，使用三个独立 JAR、每轮独立 H2 文件库和随机的控制/派发/上报/节点密钥，通过真实 HTTP 协作。验收记录共 41 项，其中 40 项为进程/API 检查、1 项记录整套浏览器验收结果。

新增节点场景实际通过：

1. Worker 自动注册真实 Handler、容量和负载，Admin 查询为在线且可派发。
2. 控制密钥不能调用心跳接口，需独立节点密钥。
3. 停止 Worker 后，Scheduler 按测试环境的 3 秒有效期判离线，保留原 generation。
4. 此时创建手动 Run，保持 READY、原 Attempt，没有 expiresAt、派发次数为零。
5. 重启同一 Worker，注册新的 session，generation 增加一。
6. 原等待 Attempt 恢复并执行成功，不额外创建第二个 Attempt。
7. 停止 Scheduler 时，Admin 节点查询返回 503，不伪造空列表。
8. 重启 Scheduler 后保留节点 generation，接受同一个 Worker 会话的继续心跳。

原手动执行、有限业务重试、超时、历史与快照、配置异步同步、重启持久化及 Cron 自动执行回归均通过。测试使用显式 HTTP 派发；生产默认 MQ 路由的节点检查在数据库集成测试中使用 Mock Publisher 验证。

## 前端与浏览器

- Vue/Vite 生产构建通过，原前端单元测试 9 项通过。
- 真实 Nginx → Admin → Scheduler → Worker 环境下，Chrome / Playwright 16 项全部通过，含新增节点页 3 项。
- 节点页覆盖真实列表/Handler/派发目标、搜索、状态过滤、查询失败保留旧记录并显示待刷新、重试恢复及离开页面后停止轮询。
- 375px 长标识、长 Handler、长队列使用专门 UI 夹具验证，不伪称真实 MQ 节点；无页面横向溢出。
- 首次查询失败不显示“还没有注册节点”，重试后的真实空状态有独立提示。
- 已实际查看桌面与 375px 全页截图，标题、统计、表格/卡片、错误状态设计和长字段换行符合现有设计规范。真实节点页测试未出现 JavaScript 页面错误。

没有 Shiplight MCP 浏览器工具，本轮使用项目已有 Playwright/Chrome 并检查本地截图，不依赖未连接的外部验证服务。

## 本机状态与证据

本轮生成被忽略的 `config/taskflow-nodes-local.yml`，不覆盖已有控制、派发、上报或 MQ 凭据，没有升级/重启现有业务实例，没有迁移业务数据库。启动/升级需按 [节点协议](worker-nodes.md) 先部署 Scheduler V6，再部署 Worker、Admin 和前端。

被忽略的 `.tools/nodes-final-verify.log` 保存最终根构建日志，`.tools/nodes-process-browser.log` 保存进程和浏览器结果。证据目录为 `.tools/execution-e2e/20261027-095809-022/`，包含 report.json、隔离服务 YAML、服务日志、workers-desktop.png 和 workers-mobile.png；各模块 target/surefire-reports 保存 JUnit 报告。

## 未覆盖的后续范围

真实 RabbitMQ 集群、V5/V6 MySQL 升级、多主机网络分区尚未实测。当前固定 Worker 路由和一个身份对应一个本地 Inbox 的约束继续保留；节点 generation 只隔离注册/心跳，不会取消已派发任务、停止旧 Handler 或为 Attempt 续租。执行租约续期、失联回收和跨节点接管仍需后续独立实现与故障验收。

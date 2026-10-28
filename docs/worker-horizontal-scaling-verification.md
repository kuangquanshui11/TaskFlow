# Worker 水平扩展验证

日期：2026-10-28。实现范围和部署步骤见 [水平扩展](worker-horizontal-scaling.md)。保持 Java 21、Spring Boot 4.1.1 与服务数据归属不变。

## 后端构建与回归

执行根目录 `.\mvnw.cmd -B verify`。Registry 1、Config Server 1、Gateway 6、Admin 70、Scheduler 104 项通过各自构建检查；Admin 中 5 项外部设施专项测试按条件跳过。Worker 新增的恢复上报测试最初受毫秒精度影响未立即进入扫描，修正测试使报告记录到期后，执行 `.\mvnw.cmd -B verify -rf :taskflow-worker` 恢复构建，Worker 66 项中 65 项通过、1 项真实 Broker 条件测试跳过。六个模块最终均验证并打包成功，合计 242 项通过、6 项条件跳过。

证据保存在忽略的 `.tools/worker-scaling-root-verify.log` 与 `.tools/worker-scaling-worker-verify.log`。条件跳过不代表相应外部设施场景已通过，真实 MQ 背压另行运行。

新增与调整的重点：

- `WorkerClusterDispatchTests`：两个节点分担新 Attempt、Handler 与满载筛选、在途计数观察、消息重发保留路由、离线原目标不改投、短租约失效后的新 Attempt/新节点、有限重试上限、错误 Worker/会话结果拒绝、路由唯一约束、在途注册路由保护及事务回滚。
- `WorkerClusterMigrationTests`：V7 升级 V8 并重复迁移，不改写旧消息、版本和硬截止；未知目标的旧 Outbox 不猜测路由或消耗发布次数。
- `WorkerExecutionTests`：重启恢复和结果重报使用原持久化执行身份，不能以新进程 session 代替。
- `WorkerNodeApiTests`：Admin 接受未标记为 HTTP 固定目标的合格 MQ 节点，继续保留状态及协议一致性校验。
- 既有派发公平性、发布确认、状态竞争和租约回归均保留。

## 真实 MySQL

执行：

```powershell
.\tools\test-run-state-mysql.ps1 -IncludeWorkerCluster -IncludeExecutionLease -IncludeDispatchFairness
```

脚本创建只监听回环地址的临时 MySQL 8.0.34 实例和随机测试 schema，不读取业务库配置。REPEATABLE READ 下 44 项全部通过：状态竞争 15、执行租约 11、派发公平性 9、Worker 集群派发 9。Scheduler V8 在该 MySQL 空库完成迁移，节点队列及路由唯一约束、目标绑定和回滚均真实执行 SQL。

日志：`.tools/worker-scaling-mysql.log` 与 `.tools/run-state-mysql/20261028-160552-280/tests.log`。结束后临时实例已停止，文件证据保留。Worker V2 在 H2 迁移和进程验证中检查，本轮没有把它描述成真实 Worker MySQL 升级验收。

## 配置与脚本

四个修改/新增 PowerShell 脚本均通过语法解析。`prepare-worker-route.ps1` 在忽略目录生成旧版和新版 Broker 两种拓扑，验证两条 quorum 队列、两条绑定、独立死信路由和策略；包含带 `.v1` 的节点标识边界。生成过程不连接 Broker、不修改账号和运行配置。

前端 `npm.cmd test` 9 项通过；集成脚本再次执行前端单元测试与生产构建，均通过。

## 真实 RabbitMQ 与双 Worker 进程

执行：

```powershell
.\tools\test-mq.ps1 -SkipBuild -SkipFailover -IncludeWorkerCluster `
  -ClusterSettingsPath .tools/mq-cluster-settings.json -AllowLegacyBroker
```

在现有 RabbitMQ 3.8.34 三节点集群创建随机隔离 vhost，四条 quorum 工作/死信队列各有三个在线副本。没有停止 Broker、修改业务 vhost 或升级业务实例。真实背压专项 1 项通过；Admin/Gateway/Scheduler/双 Worker 进程检查共 55 项通过，包含原有手动执行、Cron、配置同步、重启恢复与浏览器验收。

本次扩展的实测结果：

- 两个 Worker 使用不同队列、独立进程和独立 H2 文件 Inbox，同时完成 8 个 Run，两节点均实际执行业务 Handler。
- 逐个核对 8 个 Attempt：目标 Worker 查询返回 SUCCEEDED，另一个 Worker 查询返回 404，未出现跨 Inbox 复制。
- 停止持有执行租约的测试 Worker；原 Attempt 租约失效后成为 TIMED_OUT，同一 Run 的第二个 Attempt 在存活节点成功完成。
- 使用旧执行者身份上报原 Attempt 成功结果返回 409，不能覆盖新 Attempt。
- 重启故障测试节点后，两个节点均重新在线，管理台正确显示两个 MQ 节点及各自队列。

首次 MQ 尝试暴露 Admin 仍要求合格节点必须为固定 HTTP 目标；补齐 Admin 校验及回归测试后，以上完整流程重新运行并通过。

证据：`.tools/worker-scaling-mq.log`、`.tools/mq-e2e/taskflow_it_0a387339ab534ac1a25fc8f9d2c4bfb8/report.json` 与 `.tools/execution-e2e/20261028-162709-380/report.json`。成功轮次测试 vhost 已由脚本删除，测试进程/Nginx 已停止，文件证据保留。失败轮次按既有脚本约定保留隔离 vhost 供诊断。

## 浏览器与边界

Playwright 17 项全部通过，覆盖真实节点查询与错误恢复、离页停止轮询、MQ 非固定目标资格与满载文案、375px 长节点标识/队列换行，以及既有任务、执行和 Cron 页面。已检查桌面双节点截图及移动端长内容截图，未发现横向溢出或固定目标误标。截图位于同轮进程证据目录的 `workers-desktop.png`、`workers-mobile.png`。

本轮使用 `-SkipFailover`，没有重跑 Broker 节点停机场景；Worker 进程故障已实际注入。旧版 Broker 的死信策略仍为 at-most-once，不宣称至少一次死信转移通过。本轮没有验证机器永久丢盘、跨 Inbox 接管、网络分区下任意 Handler 物理停止或外部副作用恰好一次。升级前排空及业务幂等要求仍然适用。

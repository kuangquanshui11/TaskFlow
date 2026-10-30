# Worker 水平扩展：独立队列与固定 Attempt 目标

更新：2026-10-28。采用方案 A。MQ 模式按能力和负载选择 Worker；每个 Worker 使用独立身份、队列和持久化 Inbox。`parallelism/maxPending` 继续控制每台机器的资源。HTTP 兼容模式保留配置的固定目标，不提供多节点 HTTP 选址。

## 派发和数据归属

Scheduler 的 node 模块提供节点选择，execution 模块拥有 Attempt 的目标及执行者，dispatch 模块拥有不可变消息和路由快照。全部持久化都在 Scheduler 自有 schema；Worker 仅访问自己的 Inbox，通过版本化 HTTP 领取租约和上报结果。

1. 新 Attempt 不预先指定目标，也不领取执行租约。没有合格节点时保持 READY，Outbox 保持 WAITING，按原公平扫描间隔重新检查。
2. MQ 候选要求 ONLINE、包含快照 Handler、transport=rabbitmq、vhost/exchange 匹配 Scheduler 允许的配置，且最近心跳负载未满。队列和 routingKey 来自已认证的节点注册，不要求等于 Scheduler 的默认队列。
3. 选择时使用 `max(心跳 runningCount+pendingCount, Scheduler 已绑定且未终结的 Attempt 数)/maxPending` 比较容量占用比例，同等负载轮换。已绑定数包含待发布、排队和运行的 Attempt，减少心跳尚未更新时的集中派发。
4. 同一 Scheduler 本地事务绑定 `run_attempt.target_worker_id`、准备原始命令，并写入 Outbox 的 workerId/vhost/exchange/queue/routingKey 快照。任一步失败全部回滚。事务内不等待 Broker 或远程 HTTP。
5. Relay 发布前只检查原目标是否仍在线、支持 Handler 且路由未变；不会再选其他节点。满载或离线则推迟发布，不增加发送次数、不延长原执行硬截止。Publisher 使用 Outbox 路由，确认丢失后仍发送相同事件和目标。

负载是观测而非容量预订：多个 Scheduler 实例可能同时观察到空闲。Worker 本地原子接收检查、有限执行槽位与 MQ 背压仍是最终保护。当前选择会读取在线节点和候选的在途计数，适合本项目规模；任意规模下的精确负载均衡尚未验证。节点查询的 `dispatchEligible` 继续表示心跳观察资格，`target=true` 仅表示 HTTP 固定目标。

## 身份、队列与恢复边界

- 一个 workerId 对应一个持久化 Inbox 和一个执行进程。重启复用原数据库、workerId 和队列；不同节点不得共用 Inbox schema、数据库账号或队列。
- Scheduler 约束同一 vhost/queue 唯一，以及同一 vhost/exchange/routingKey 唯一，避免两个注册节点通过同一路由接收到命令副本。Broker 管理员仍必须保证一个 routingKey 只绑定对应工作队列；Scheduler 不检查 Broker 上的全部绑定。
- 节点存在已绑定且未终结的 Attempt 时，新会话不得变更能力、transport 或路由。显示名称和容量可以修改；能力/路由调整应先停止新派发并排空旧工作。数据库丢失后，复用 workerId 无法恢复原 Inbox。
- Node 注册会话与执行租约会话独立。首次领取必须匹配 Attempt 目标；领取后绑定 ownerWorkerId/ownerSessionId，其他进程不能用相同 workerId 接管旧执行。
- Worker 从 ACCEPTED 转 RUNNING 时，将结果所属 workerId/sessionId 同步写入本地记录；恢复、保存和重报始终使用该记录的原执行身份，不能用新进程会话代替。
- Worker 在 Inbox 提交后 ACK，Broker 不再保留这条 delivery。某节点宕机后，其他节点不会读取它的 Inbox，也不会立即接管它已 ACK 的排队任务。未领取短租约的任务需要等待硬截止；已领取任务可在短租约过期后由 Scheduler 收尾。

## 租约失效与业务重试

Scheduler `taskflow.execution.retry-on-lease-loss` / `TASKFLOW_RETRY_ON_LEASE_LOSS` 默认 true。短租约或硬截止失效后，原 Attempt 标记 TIMED_OUT，取消尚未完成的 Outbox；按快照 `maxAttempts/retryIntervalSeconds` 为同一 Run 新增 Attempt。新 Attempt 使用新 UUID 和递增的 leaseVersion，重新选择合格节点。达到上限则 Run 失败，不创建无限重试。

设为 false 可保留此前“租约失效后直接终结”的行为。发布确认不确定或发布次数耗尽仍不立即新增业务 Attempt；显式 Handler 失败继续按 retryable 和快照策略处理。Worker 已报告的执行超时、重启未知副作用失败仍保持原有非重试语义；只有 Scheduler 观察到租约失效时使用上述新策略。

租约失效不证明旧进程或外部副作用已经停止。可能出现结果已产生但未及时上报，随后新 Attempt 再执行的情况。业务 Handler 必须使用 Run 范围的幂等键，下游若需 fencing，应原子校验业务版本；不能承诺外部副作用恰好一次。

## 协议变化与升级

MQ `taskflow.dispatch.v1` 命令格式不变，目标路由保存在 Scheduler。内部 `POST /internal/v1/attempts/updateResult` 新增必填 `workerId/sessionId`，仍使用结果上报密钥；缺少字段为 400，目标、已领取会话、版本或有效租约不匹配为 409。终态相同结果也须先匹配执行身份，才可幂等确认。租约接口路径不变，首次领取改为校验 Attempt 目标。Run 查询的 Attempt 增加 nullable `targetWorkerId`。

Scheduler Flyway V8 增加目标与路由列、索引和路由唯一约束；Worker Flyway V2 增加结果所属身份。升级前排空在途和待重报工作，备份各自数据库，再先升级 Scheduler、后升级所有 Worker。新增必填结果字段不支持新旧 Worker 混用；业务实例和数据库需按上述顺序显式升级。

升级前检查 `worker_node` 是否存在重复的 `(dispatch_vhost,dispatch_exchange,dispatch_routing_key)`；有冲突应先修复 Broker 绑定和节点配置，否则唯一约束迁移会失败。旧版已派发 Attempt/Outbox 没有可靠的目标快照，新版不会根据当前配置猜测目标、改投或重发；其租约到期后按重试策略收尾。旧 Worker 的历史结果也不会补造执行会话。未排空升级会影响在途结果确认，必要时在升级期间显式关闭租约失效重试。

## 新增一个节点

先准备独立 Worker schema/账号和持久化磁盘。三个服务之间不共享业务表；多个 Worker 实例也不共用同一 Inbox schema。节点使用同一 Scheduler HTTP 接口、节点密钥及结果上报密钥，并配置所在 Broker 的连接地址。凭据保存在环境变量或忽略的本机 YAML，不放配置中心或提交到 Git。

可以先生成不含凭据的 Broker 拓扑片段：

```powershell
.\tools\prepare-worker-route.ps1 -WorkerId worker-02
# 本项目现有 RabbitMQ 3.8 集群需要显式采用旧版死信兼容策略：
# .\tools\prepare-worker-route.ps1 -WorkerId worker-02 -LegacyBroker
```

输出位于忽略的 `.tools/worker-routes/worker-02.json`，包含两条三副本 quorum 队列、工作/死信绑定和独立安全策略。脚本只生成文件，不连接 Broker。审核后由 Broker 管理员在已有 vhost/exchange 下导入，并确认三个副本在线。为此节点配置独立消费账号：configure/write 不开放，read 仅允许 `^taskflow\.worker-02\.v1$`；Scheduler 继续仅有共享 dispatch exchange 的写权限。升级旧版 Broker 的限制仍见 [可靠派发](reliable-dispatch.md)。

每个新 Worker 在独立终端或进程环境设置身份和非秘密参数，例如：

```powershell
$env:TASKFLOW_WORKER_ID = 'worker-02'
$env:TASKFLOW_WORKER_PORT = '8083'
$env:TASKFLOW_MQ_QUEUE = 'taskflow.worker-02.v1'
$env:TASKFLOW_MQ_ROUTING_KEY = 'worker-02'
$env:TASKFLOW_MQ_DLQ = 'taskflow.worker-02.dead.v1'
$env:TASKFLOW_MQ_DEAD_ROUTING_KEY = 'worker-02.dead'
$env:TASKFLOW_WORKER_PARALLELISM = '4'
$env:TASKFLOW_WORKER_MAX_PENDING = '32'
# 还需设置此节点自己的 TASKFLOW_WORKER_DB_URL/USERNAME/PASSWORD、
# TASKFLOW_MQ_USERNAME/PASSWORD，以及所在环境的 MQ 地址、Scheduler 地址和内部密钥。
java -jar taskflow-worker/target/taskflow-worker-0.0.1-SNAPSHOT.jar --spring.profiles.active=cluster
# 需要 Eureka/集中配置时使用 --spring.profiles.active=cloud,cluster
```

`application-cluster.yml` 要求显式提供身份、端口、数据库和 MQ 账号及队列参数，缺少配置则启动失败。默认单节点启动方式及默认队列名保留；MQ 动态选择不再受 Scheduler 的 `TASKFLOW_TARGET_WORKER_ID` 限制。不要通过 `dispatch-gating-enabled=false` 配置集群，该开关只服务于显式兼容/隔离测试。

上线后通过 `/api/v1/workers/select` 确认多个节点在线，再从 Run 详情核对每次 Attempt 的 targetWorkerId/ownerWorkerId。停止节点后检查新任务能进入其他节点，旧 Attempt 不被改投。

## 验证

```powershell
.\mvnw.cmd verify
.\tools\test-run-state-mysql.ps1 -IncludeWorkerCluster -IncludeExecutionLease -IncludeDispatchFairness
.\tools\test-mq.ps1 -SkipBuild -SkipBrowser -SkipFailover -IncludeWorkerCluster `
  -ClusterSettingsPath .tools/mq-cluster-settings.json -AllowLegacyBroker
```

MySQL 脚本创建独立临时实例/schema。MQ 脚本只使用随机 `taskflow_it_` vhost，新增第二份队列及第二个 Worker/H2 文件数据库，核对双节点执行和 Inbox 隔离，再停止持有租约的测试 Worker，检查新 Attempt 在存活节点完成以及旧结果被拒绝。`-SkipFailover` 仅跳过 Broker 节点停机，不跳过测试 Worker 进程故障。实际执行结果见 [扩展验证记录](worker-horizontal-scaling-verification.md)。

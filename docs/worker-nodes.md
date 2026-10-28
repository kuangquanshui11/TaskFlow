# Worker 注册、心跳与节点查询 v1

更新：2026-10-27。本模块负责节点身份、能力、心跳有效期和派发资格检查；2026-10-28 增加 MQ 多节点选择，完整规则见 [水平扩展](worker-horizontal-scaling.md)。继续使用 Java 21、Spring Boot 4.1.1、Spring MVC、JdbcTemplate、MySQL/Flyway 和 Vue 3/Vue Router；内部 HTTP 已接入 OpenFeign、LoadBalancer 和 Resilience4j。cloud 模式的 Eureka 负责网络地址发现，本模块仍负责业务在线状态、Handler、会话及 Worker 身份，两者不可替代。见 [微服务技术栈](microservice-platform.md)。

## 数据归属与包结构

- Scheduler 的 node 业务使用 controller、service/impl、mapper、dto、model、config、exception，拥有节点和会话历史。
- Worker 的 node 业务使用 service/impl、client、dto、config、exception，调用本服务 IExecutionService 读取真实执行负载，再经 HTTP 上报；不创建无用途的 Controller 或 Mapper。
- Admin 的 node 业务使用 controller、service/impl、client、dto、config、exception，只代理 Scheduler 查询，不保存另一份节点表，也不经过 Job 详情缓存。
- 前端通过 Nginx 的同源 `/api/` 查询 Admin，不拿内部地址、身份密钥或节点会话 UUID。

Scheduler Flyway V6 新增 `worker_node`（每个 workerId 的当前会话、能力和心跳）及 `worker_node_session`（已使用的 sessionId/generation 历史）。表只位于 Scheduler schema；Worker 本地 Inbox 和执行恢复仍归 Worker。

## 身份与会话

workerId 为稳定配置标识，使用 1–100 个小写字母、数字、点、下划线和连字符，首字符为字母或数字。限制小写避免 MySQL 默认不区分大小写的排序规则造成两个逻辑身份混用。默认 `worker-default`；displayName 为可读名称，不参与幂等判断。

每个 Worker 进程启动生成一个 UUID sessionId。首次注册分配 generation=1；注册成功后依次发送 sequence=1、2… 的心跳。HTTP 超时不更换 sessionId，下一周期继续使用原会话；没有在本地事务中等待远程 HTTP。

同一 sessionId 重试注册必须保持原能力和配置，返回原 generation 和有效期，不刷新最后心跳。不同会话不能覆盖仍在线的同 workerId；旧会话过期后，新进程才可注册并将 generation 加一。历史 sessionId 保留，旧进程即使重发注册也不能取代新进程。

心跳同时校验 workerId、sessionId、generation 和 sequence。更大序号更新状态；重复相同序号和相同负载幂等返回；同序号不同负载返回 409；较旧序号不修改负载或延长有效期。旧 generation/session 返回 409。Scheduler 数据丢失后的整体重建不属于此协议的自动恢复保证。

注册最多允许 100 个不重复 Handler，parallelism 为 1–64，maxPending 为 parallelism–10000。心跳 runningCount 不得超过 parallelism，runningCount+pendingCount 不得超过 maxPending。负载来自 Worker 本地 Inbox 的 RUNNING/ACCEPTED 记录，是观测值而不是容量预订或精确 Handler 开始时间。

MQ 元数据包含 vhost、exchange、queue 和 routingKey，不包含凭据或回调地址。同一 vhost/queue 及 vhost/exchange/routingKey 分别只能属于一个 workerId，避免竞争同一 Inbox 边界或向多个队列复制命令。每个 Worker 身份仍必须绑定原本的 Inbox 数据库、且只启动一个执行进程；不提供同一 Attempt 在不同本地数据库之间的接管；故障重试新增 Attempt。

## 在线判定与派发

有效期由 Scheduler 接收时间计算，不接受 Worker 自报时间。默认 `expiresAt = 最后有效心跳时间 + 30 秒`；注册首次接收也建立有效期。`expiresAt > Scheduler 当前时间` 为 ONLINE，等于或早于则为 OFFLINE。查询和派发实时比较同一权威时间，不依赖后台扫描改状态，也不使用 Redis 存活标记。

Scheduler 在 MQ 模式按在线状态、Handler、transport、允许的 vhost/exchange 和负载选择节点，并在本地事务中绑定 Attempt 目标及 Outbox 路由。HTTP 模式继续使用 `taskflow.nodes.target-worker-id` 指定的固定节点。`runningCount + pendingCount >= maxPending` 时节点仍 ONLINE，但 `dispatchEligible=false`，推迟派发准备和 MQ 发布；HTTP 派发仍使用服务配置的 Worker URL，不访问 Worker 上报的任意地址。没有匹配节点或节点满载时，新的 Attempt 保持 READY、Outbox 保持 WAITING，不消耗执行租约或派发次数。负载观察不能替代容量预订，Worker 的本地容量检查与 MQ 背压仍是最终保护。

资格不满足时，将 Attempt 的 `nextDispatchAt` 持久化推迟，避免同一批不可派发任务反复占据前 100 条。正常任务可在后续轮次进入候选；支持能力恢复后，在原记录的下一次检查时间重新判断，不因一次心跳立即绕过等待时间。原 Attempt 和 leaseVersion 保持不变。

独立 MQ Relay 在发布前再次检查节点，节点过期时推迟 Outbox 的 `nextPublishAt`，不领取发送租约、不增加发布次数。READY 和领取租约已经过期的 SENDING 都遵守这个时间；仍有效的 SENDING 不会被延后或接管。已经准备的 Attempt 继续受原 expiresAt 约束，不能借暂停发布无限延长执行租约。心跳恢复后，尚有效的原 Attempt 在到达下次检查时间后可继续派发。

这些检查是本次准备/发布时的观察，不能与远程消费构成原子操作。离线不会撤回已在 Broker 中的消息，也不取消旧 Handler。节点 generation 只隔离注册/心跳，不能代替 Attempt 的 leaseVersion；执行有界续租已实现；租约失效可为同一 Run 新增 Attempt 并重新选择节点，不能把原 Attempt 直接改投。

## HTTP API

所有内部接口使用版本化 JSON，错误为 `{code,message}`。Worker 注册与心跳使用独立 `TASKFLOW_NODE_TOKEN`；Admin 查询使用既有 `TASKFLOW_CONTROL_TOKEN`。空密钥拒绝访问，两个角色不能互换。

| 方法 | 路径 | 参数与行为 |
| --- | --- | --- |
| POST | /internal/v1/workers/insert | 注册进程会话；workerId、displayName、sessionId、handlerKeys、parallelism、maxPending、dispatchTransport 及 MQ 路由元数据 |
| POST | /internal/v1/workers/updateHeartbeat | workerId、sessionId、generation、sequence、runningCount、pendingCount |
| GET | /internal/v1/workers/select | keyword（标识或名称的字面包含匹配）、state=ALL/ONLINE/OFFLINE、page、pageSize |
| GET | /internal/v1/workers/selectById | workerId |
| GET | /api/v1/workers/select | Admin 代理上述分页查询，沿用 ApiResponse |
| GET | /api/v1/workers/selectById | Admin 代理上述详情查询，沿用 ApiResponse |

注册及心跳返回 workerId、sessionId、generation、lastSequence、expiresAt。注册时 MQ transport 必须提供完整 MQ 元数据，HTTP transport 不提供；同会话的注册快照不可修改，修改能力需按进程重启流程注册新会话。

分页返回 records、筛选后 total、page/pageSize、全局 summary（total/online/offline）和 observedAt；状态和统计以同一次 Scheduler 观察时间计算。每条记录含节点名称、generation、Handler、执行容量、最近负载、路由、status、target、dispatchEligible、registeredAt、lastHeartbeatAt 和 expiresAt，不暴露 sessionId 或注册指纹。

查询参数错误为 400；节点不存在为 404；活跃身份占用、旧会话、同序号冲突或队列占用为 409。Admin 对 Scheduler 超时、不可用或不合法响应返回 503，不能用空列表或模拟在线替代失败。查询 GET，注册和心跳 POST，遵守动作路径约定。

## 周期、失败与配置

Worker 使用独立 `worker-heartbeat-` 单线程执行固定延迟周期，不与 Handler 或结果重报共享调度线程。每周期至多一次注册和一次心跳，无立即无限重试；失败留待下一周期。Heartbeat 404/409 后，用原 sessionId 重新注册；其他进程已接管时，原 session 仍被拒绝。连接与请求均有超时，数据库查询完成后再等待 HTTP。

| 配置或环境变量 | 默认与用途 |
| --- | --- |
| TASKFLOW_NODE_TOKEN | 无默认凭据；Scheduler 与 Worker 共享独立随机密钥 |
| TASKFLOW_WORKER_ID | worker-default；稳定身份，不应随进程 PID 改变 |
| TASKFLOW_WORKER_NAME | 默认执行节点；显示名称 |
| TASKFLOW_TARGET_WORKER_ID | worker-default；Scheduler HTTP 模式的固定目标；MQ 动态选择不使用该值 |
| TASKFLOW_NODE_HEARTBEAT_ENABLED | true；Worker 心跳开关，关闭后节点将按超时离线 |
| TASKFLOW_SCHEDULER_URL | 从现有服务地址配置读取，供 Worker 上报和 Admin 查询 |
| taskflow.nodes.heartbeat-interval | Worker 默认 5 秒 |
| taskflow.nodes.initial-delay | Worker 默认 200ms |
| taskflow.nodes.offline-timeout | Scheduler 默认 30 秒 |
| taskflow.nodes.dispatch-gating-enabled | Scheduler 默认 true；关闭仅用于明确的兼容/隔离测试，不再据心跳阻止派发 |
| taskflow.dispatch.node-recheck-delay / TASKFLOW_NODE_RECHECK_DELAY | Scheduler 默认 2s，范围 100ms–5m；派发准备和 MQ 发布遇到无合格节点时的检查间隔 |
| taskflow.nodes.connect-timeout / request-timeout | HTTP 默认 2 秒 / 3 秒 |

连接、心跳、有效期和限额统一在各服务 YAML；测试使用独立 test profile，已有执行测试明确关闭节点 gating，新增节点测试开启并使用可控 Clock。三进程测试用独立 YAML 缩短周期，不改变业务配置。

周期和离线阈值应留足超时与重试间隔。Scheduler 多实例应同步 UTC 时钟；本协议不消除网络分区，也不通过心跳证明某个外部副作用没有执行。调度线程能力参考 [Spring 官方调度文档](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)。

## 页面与部署

管理台新增 `/workers`，侧栏“工作节点”已启用，沿用原 Vue 3、设计令牌和系统字体。支持节点统计、关键字与状态筛选、分页、手动/自动刷新、Handler 与路由展示。页面展示更新时间和 Asia/Shanghai 时区；离开页面或隐藏标签页会取消请求并停止轮询。查询失败保留上次数据，状态显示“待刷新”，不继续宣称在线；首次失败和真实空列表有不同提示。移动端转为节点卡片，长标识、队列和 Handler 可换行。

升级前准备同一份节点身份密钥：

```powershell
.\tools\prepare-node-identity.ps1
```

该工具只创建被忽略的 `config/taskflow-nodes-local.yml`，已存在则保留，不修改其他凭据、不启动服务。现有 prepare-local-execution.ps1 也会调用此工具。分机部署时通过环境变量或本机 YAML 配置同一 TASKFLOW_NODE_TOKEN，不将文件提交或分发到前端。

先升级 Scheduler 应用 V6，再升级 Worker 和 Admin，并更新前端静态文件。首次升级时，Scheduler 在 Worker 注册成功前保留新任务为 READY。Worker 重启的新会话需要等旧会话超时，默认可能等待 30 秒；不要靠不断更换 workerId 绕过身份检查。MQ 尚未部署时继续按 [可靠派发](reliable-dispatch.md) 显式选择 HTTP 模式。

本轮不自动升级当前业务实例或业务数据库。实现测试与运行验证见 [节点验证记录](worker-nodes-verification.md)。

2026-10-27 补充扫描公平性修复，超过单批容量的资格不匹配任务不再阻塞后续正常任务。复现、MySQL 回归和租约边界见 [派发扫描公平性验证](dispatch-fairness-verification.md)。

# 固定 Worker 执行租约

Scheduler 拥有执行资格和 Run/Attempt 权威状态，Worker 通过版本化 HTTP API 领取和续租。节点心跳仍只代表进程存活。本轮不引入跨 Worker 接管，不修改派发消息、Inbox fingerprint 或续租过程中的 `leaseVersion`。

## 时间边界

| 时间 | 含义 |
| --- | --- |
| `executionDeadline` | 首次派发的 `startedAt + snapshot.timeoutSeconds`，上限为 `expiresAt`；Handler 必须在此前退出 |
| `expiresAt` / `hardDeadline` | 首次派发时确定的执行预算加结果宽限，重派和续租都不改变 |
| `leaseUntil` | Worker 领取执行资格后的短租约；每次更新为 `min(Scheduler 当前时间 + duration, hardDeadline)` |

首次派发的开始时间和配置快照不变，因此重启或更改租约宽限配置不会移动已有执行的截止时间。排队消耗执行预算；Broker、Inbox 排队期间不续租。`leaseUntil` 为空时按原 `expiresAt` 收敛，实际调用 Handler 前必须领取执行资格。

续租上界是硬截止时间，不设置续租次数上限。Handler 最大执行时间和短租约分开维护；已绑定执行者可在执行截止后、硬截止前继续续租以保存结果，不能继续执行业务。首次领取已无执行预算时拒绝启动 Handler。

## 内部 HTTP 契约

`POST /internal/v1/attempts/updateLease`，使用 `X-Taskflow-Token` 结果上报密钥；Gateway 不开放此路径。

请求字段：`attemptId`、`runId`（UUID）、正整数 `leaseVersion`、`workerId`、`sessionId`（UUID）。`workerId` 使用节点配置的 Worker 标识；`sessionId` 是 Worker 进程内生成的独立执行会话，进程重启不复用，不等同于节点注册会话或 generation。

成功返回 HTTP 200：

```json
{
  "attemptId": "a0e7320d-aef8-4bf1-82e4-96ac769ea38b",
  "runId": "76940f59-1911-491a-b07a-f6746d41ee33",
  "leaseVersion": 1,
  "workerId": "worker-default",
  "sessionId": "8b215277-8dc0-46e2-a218-71478c7a0321",
  "serverTime": "2026-10-28T00:00:00Z",
  "leaseUntil": "2026-10-28T00:00:30Z",
  "executionDeadline": "2026-10-28T00:01:00Z",
  "hardDeadline": "2026-10-28T00:01:10Z"
}
```

缺少/错误密钥返回 401；参数校验失败返回 400；不存在、Run 不匹配、非当前 Attempt、版本不匹配、终态、租约过期、执行者不匹配返回 `409 / STALE_LEASE`。首次领取只允许当前固定派发目标；领取后固定绑定 Worker 与执行会话，不因路由配置变化更换所有者。

Scheduler 在本地事务内按 Run → Attempt 加锁，随后读取当前状态。SQL 同时检查原状态、版本、原租约有效性、硬截止时间和执行者；影响行数必须为 1。首次领取同时将 Attempt/Run 标为 RUNNING，任一转换失败整体回滚。已领取资格的执行不再因原 HTTP 派发响应丢失而被派发失败分支终结。超时扫描锁内复查，不能用扫描候选的旧状态覆盖刚续期的租约。结果提交同时校验硬截止和短租约，相同已确认终态仍幂等确认。

## Worker 生命周期

1. Inbox 提交后才确认接收；领取本地执行记录后，先向 Scheduler 领取执行资格，失败则不调用 Handler，不请求自动业务重试。
2. 首次领取在 Handler 调用前完成。后续续租由专用 `taskflow-lease-*` 网络线程执行，线程数受执行并行度约束；超时/失租检查由独立 `taskflow-timeout-*` 计时器执行。
3. Worker 校验完整响应身份和时间边界，以请求发出时的 `System.nanoTime()` 加 Scheduler 返回的剩余时长计算保守期限，扣除安全余量。网络往返消耗预算，不依赖两机墙钟绝对值相同。
4. 短暂网络故障只在最后确认的安全期限内等待下一次显式续租。明确拒绝、不合法响应或安全期限耗尽后永久取消本地资格；迟到成功响应不能复活。Feign/LoadBalancer 不透明重放 POST。
5. Handler 结束前后均检查上下文；失租或超时以非自动重试的失败收尾。中断只发送给仍在运行的 Handler，不中断随后的结果保存。
6. 结果保存期间继续续租，保存结束后取消续租并释放上下文；执行槽位直到 Handler 真正退出且保存结束才释放。结果上报仍使用原持久化扫描和有限次数，在最后确认的租约与硬截止内尝试；落库后不为等待上报无限保留内存上下文，也不保证网络长期故障时结果必达。

Worker 重启后的 RUNNING 记录仍按 `WORKER_RESTARTED` 表示副作用未知，不重新调用 Handler；ACCEPTED 记录保留，实际启动前必须领取有效资格。旧会话不能通过换 sessionId 接管已有执行。

## Handler 与外部副作用

Handler 接收 `Snapshot` 和 `ExecutionContext`。上下文提供 Run/Attempt 标识、Attempt 序号、租约版本、截止时间、取消状态及 `checkActive()`。分批处理和远程写操作之前应调用 `checkActive()`；示例慢任务在等待前后检查。

| 标识 | 使用约定 |
| --- | --- |
| `runId` | 业务幂等的稳定范围；自动重试复用，人工再次执行生成新的 Run |
| `context.businessIdempotencyKey(operation)` | `runId:operation`；同一次运行的不同动作/业务项使用各自稳定 operation |
| `attemptId` | 一次尝试的 Inbox 去重、追踪与恢复标识；不能单独用于跨 Attempt 业务幂等 |
| `leaseVersion` | fencing 版本；续租不改变。当前等于 Attempt 序号，仅能在同一 Run 内比较 |

下游需将业务幂等键的唯一约束和实际副作用放在自己的同一事务中。若下游还要 fencing，应在资源写入的同一事务中校验并推进版本；携带 token 而不校验不形成保护。同一 Run 的后续 Attempt 可以拒绝较旧 Attempt 的写入；多个 Run 操作同一业务资源时，需要该资源自己的递增 fencing token 或业务版本，不能直接比较不同 Run 的 `leaseVersion`。

`checkActive()` 与远程写入之间仍有时间窗口。长 GC、线程忽略中断及已发出的外部请求都可能在租约失效后产生副作用；续租和本地中断不能撤销这些操作。下游去重/fencing 也不自动保证“到期后任何写入都失败”，严格的有效性约束需要下游参与授权校验。本轮未引入外部业务系统，不声称已实现任意副作用恰好一次。

## 配置、升级与验证

Scheduler `taskflow.execution.lease.duration` 默认 30s，允许 10s–5m，通过 `TASKFLOW_EXECUTION_LEASE_DURATION` 提供。Worker `renewal-interval` 默认 5s（100ms–1m），`safety-margin` 默认 6s（100ms–30s），`watchdog-interval` 默认 100ms（10ms–1s）；前两项可分别通过 `TASKFLOW_EXECUTION_LEASE_RENEWAL_INTERVAL`、`TASKFLOW_EXECUTION_LEASE_SAFETY_MARGIN` 提供。配置写各服务 YAML，默认重启生效。

应满足：续租间隔 + 一次请求与调度开销 < 租约时长 − 安全余量。安全余量应覆盖时钟漂移和检测开销；网络长时间阻塞时宁可失去资格，也不越过最后确认的本地期限。接近硬截止时可用时间自然缩短，续租不能扩展它。

Scheduler Flyway V7 仅新增 nullable `lease_until`、`owner_worker_id`、`owner_session_id` 和索引；保留既有状态、消息、版本及截止时间。升级前排空在途工作，先升级 Scheduler 再升级 Worker；新 Worker 依赖新续租接口，不兼容旧 Scheduler。旧 Worker 的任务没有新执行上下文，不能将混合版本运行描述为已具备完整保护。本次代码提交不替换已有运行实例。

验证命令与实际结果见 [执行租约验收](execution-leases-verification.md)。

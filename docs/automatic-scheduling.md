# Cron 自动触发、Run 创建和并发去重

Scheduler 已实现到期扫描 → 创建 Run/首次 Attempt → 推进计划 → 派发 → Worker 执行及结果上报。使用 Java 21、Spring Boot、JdbcTemplate、本服务 MySQL 与 Flyway，不增加跨服务应用依赖或共享数据库。默认派发已接入 [事务 Outbox 与 RabbitMQ](reliable-dispatch.md)，HTTP 为显式兼容通道。

## 到期触发事务

`IPlanTriggerService.scanDue` 每轮按计划时间与 Job ID 查询最多 100 个候选 Job：`READY`、启用、未删除且 `next_fire_time <= 当前时间`。候选查询不承担锁定或去重。

每个候选在独立的 Scheduler 本地事务中：

1. `SELECT ... FOR UPDATE` 锁定本服务的计划行，重新读取最新版本并检查状态与到期时间。
2. 使用锁定后的当前时间、配置快照、时区和错过策略作出本轮决定。
3. 需要触发时，调用 `IRunService.insertScheduled`，保存 Run 和首次 READY Attempt。该方法要求已有本地事务。
4. 将下一时间推进至严格晚于本次决策时间，更新计算状态与基准；SQL 同时检查版本、原计划时间和有效状态。
5. 一起提交。任一步失败，Run、Attempt 和计划推进一起回滚。

这个事务内没有远程调用。MQ 模式下还会与首次 Attempt 同时写入 WAITING Outbox；`run-dispatch-` 准备不可变命令与执行租约，独立 `dispatch-relay-` 在 SQL 事务之外发布并等待 MQ 确认。显式 HTTP 模式继续使用原派发协议。两种模式沿用结果上报、超时、租约与有限业务重试。

一条计划失败不阻止其他 Job；唯一键竞争或瞬时锁冲突在新事务中最多重试两次，仍失败则留待后续扫描。数据库失败不会提前推进计划；损坏的旧配置快照标记 INVALID，等待检查后重新保存配置。

## 并发与身份

Scheduler V4 迁移在 `run_execution` 增加：

| 字段或约束 | 语义 |
| --- | --- |
| trigger_type | MANUAL 或 CRON；旧运行回填为 MANUAL |
| scheduled_at | CRON 对应的原计划时间，UTC DATETIME(3)；人工执行为 null |
| UNIQUE(job_id, scheduled_at) | 同一 Job、同一计划时间只能创建一个定时 Run，与配置版本无关 |
| CHECK 触发来源与时间 | MANUAL 必须无计划时间，CRON 必须有计划时间 |

多个 Scheduler 同时发现同一计划时，由计划行锁串行判断，数据库唯一约束兜底。没有依赖单 JVM 锁或 Redis 分布式锁。即使计划恢复后重新指向已经创建的轮次，仍沿用原 Run，不新建 Attempt，再推进计划。

人工执行的 `scheduled_at` 为 null，唯一键允许多个 null，不会合并两次独立人工执行；人工重发仍按原 requestKey 幂等。自动失败重试沿用 Run 并新增 Attempt，不重新创建该计划时间的 Run。

此约束保证保留的数据库记录中每个计划轮次至多一个 Run，不承诺任意外部业务副作用恰好执行一次。Run 历史及唯一键属于去重依据，本阶段不提供删除历史或去重记录归档。

## 正常延迟与错过策略

默认扫描间隔 500 毫秒，`misfire-grace` 为 5 秒。计划时间严格早于 `当前时间 - 宽限` 才判定为错过；恰好等于边界按正常到期处理，避免所有正常扫描延迟都被当作错过。

| 场景 | 处理 |
| --- | --- |
| 计划已到且在宽限内 | 创建该计划时间的一个 Run |
| 超过宽限，SKIP | 不创建 Run，下一计划推进到当前时间之后 |
| 超过宽限，FIRE_ONCE | 为原持久化计划时间创建一个 Run，再推进到当前时间之后 |

每个 Job 每次处理最多创建一个 Run。无论积压多少历史轮次，下一时间都以本次决策时间为基准，不逐条补跑所有错过的时间。宽限内发生多轮延迟时也合并处理本次持久化的到期轮次，不在同一事务中追赶后续历史轮次。

FIRE_ONCE 的 scheduledAt 保留原本到期时间，createdAt 表示恢复后实际创建时间，两者可能相差很久。既有 NO_FUTURE_FIRE 规则保留：没有可保存的下一时间则清空 nextFireTime，停止后续扫描。

补偿范围只包括 Scheduler 已保存的到期计划。尚未同步的 Admin 配置、刚启用的任务和 V2 老副本首次补算，按实际接收或初始化时间计算未来计划，不推测此前不存在的执行轮次。

## 配置变更与停机恢复

- 配置同步和到期触发锁定同一计划行。修改、停用、删除与触发按取得锁的顺序串行化，不混用两个版本的快照。
- 若触发事务先提交，随后停用不会取消已经创建或正在执行的 Run；停用副本提交后，不再新增该计划的后续 Run。
- 配置变更不改变已创建 Run 的快照或来源；旧同步事件仍不能覆盖新副本。
- 提交前进程退出：事务回滚，原时间仍可再次扫描；提交后退出：持久化 Run/Attempt 由派发扫描恢复。
- Scheduler 重启保留原计划时间，再按宽限及 SKIP/FIRE_ONCE 处理；查询本身不推进计划。
- 多实例共享 Scheduler 自己的 schema，并保持 UTC 时钟同步；每服务仍只访问自身数据。

## 配置与界面

参数统一位于 Scheduler `application.yml`：

```yaml
taskflow:
  plans:
    trigger:
      enabled: ${TASKFLOW_PLAN_TRIGGER_ENABLED:true}
      scan-interval: 500ms
      batch-size: 100
      misfire-grace: 5s
```

使用独立 `plan-trigger-` 单线程，后台补算和 Run 派发另有线程。关闭某节点的 enabled 只停止该节点到期扫描，不清空计划；恢复后按错过策略处理。集群暂停需要关闭所有 Scheduler 的扫描，或通过 Admin 停用相应 Job 并等待同步确认。

`GET /internal/v1/plans/selectByJobId` 和 Admin 的 `GET /api/v1/job-sync/selectPlanByJobId` 增加 `automaticTriggerEnabled`，表示响应 Scheduler 节点的实际开关，不代表整个集群停止或 Worker 健康。

既有 Run 分页、按 ID 及 requestKey 查询响应增加 `triggerType`、`scheduledAt`；人工创建请求不增加入参。Worker 派发和结果上报协议保持不变。

详情展示下一计划时间和节点触发状态。启用的 CRON 计划及运行历史在详情打开时持续刷新；关闭后停止请求。运行列表区分定时/手动触发，详情显示原计划时间及其时区。同步成功、创建 Run 和执行成功分别呈现，避免将接受计划等同于任务成功。

## 验证与升级

执行根目录 `mvnw.cmd verify`，再执行 `tools/test-execution.ps1 -SkipBuild`。默认使用隔离 H2；`PlanTriggerTests` 还可使用 `application-test-mysql.yml` 指定的本机空 `taskflow_it_plan_migration_<唯一后缀>` schema 验证真实 MySQL 锁与唯一约束。迁移用例与触发用例使用不同的专用 schema，避免回退已升级的版本。

先升级 Scheduler，让 Flyway 自动应用 V4，再升级 Admin 和前端；Worker 无需协议变更。默认自动触发开启，升级后已启用的到期计划将按配置的错过策略处理。需要分阶段启用时，先在所有 Scheduler 设置 `TASKFLOW_PLAN_TRIGGER_ENABLED=false`，核对计划后再开启。本次代码验收不修改当前业务实例。

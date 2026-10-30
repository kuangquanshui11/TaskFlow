# Scheduler 下一计划时间

本文说明 Cron 计划计算、持久化和查询。后续已接入到期扫描和自动创建 Run，具体事务、去重、错过策略与开关见 [自动调度](automatic-scheduling.md)。Scheduler 不执行实际 Handler，任务仍由 Worker 执行。

## 数据与计算规则

Scheduler 自己的 V3 迁移为 `schedule_plan` 增加：

| 字段 | 语义 |
| --- | --- |
| next_fire_time | 下一计划时间，UTC `DATETIME(3)`；不适用时为 NULL |
| calculated_at | 最近一次实际计算的基准时间，UTC `DATETIME(3)` |
| calculation_state | 计算状态，见下表 |

`PlanCalculationServiceImpl` 实现 `IPlanCalculationService`。启用的 CRON 配置使用 Spring 六字段 Cron 和 IANA 时区，以 Scheduler 接受配置时的 UTC 时钟计算**严格晚于基准**的下一时间。使用 `ZonedDateTime` 处理夏令时：不存在的本地时间被跳过，重复的本地时间保留时区偏移语义。闰日等日期规则由相同 Cron 计算器处理。

例如基准为 `2026-10-26T00:00:00Z`，`0 0 9 * * *` 配合 `Asia/Shanghai`，保存的下一时间为 `2026-10-26T01:00:00Z`；改为 UTC 后为 `2026-10-26T09:00:00Z`。

| calculationState | nextFireTime | 说明 |
| --- | --- | --- |
| PENDING | null | 旧版副本升级后等待后台补算 |
| READY | 非空 | 已计算并保存；是否扫描还取决于响应节点的 automaticTriggerEnabled |
| DISABLED | null | CRON 任务已停用 |
| MANUAL | null | 手动任务，无定时计划 |
| DELETED | null | 删除墓碑，副本强制停用 |
| NO_FUTURE_FIRE | null | 在可计算及数据库可保存的范围内没有下一时间，例如 2 月 31 日 |
| INVALID | null | 旧版持久化快照异常；检查后重新保存配置 |

删除优先于其他状态；手动类型优先于启停状态。没有下一时间时不会伪造日期，也不会把空时间解释为任务失败。

## 配置变更与一致性

- 新版本的副本、配置快照和计算结果，与 Inbox 在同一个 Scheduler 本地事务内提交或回滚。
- 首次接收，以及 Cron、时区、执行方式、启停或删除标志变化时重新计算。重新启用使用当次接收时间，不补跑停用期间的时间。
- 名称、说明、Handler、参数和执行策略变化，若未改变调度规则，则保留已计算的时间与计算基准，防止频繁编辑把临近计划不断推迟；副本仍保存最新配置版本和快照。
- 相同事件重发及旧版本晚到沿用原幂等和版本判断，不移动计划时间。
- 查询和服务重启只读取已保存结果，不用“当前时间”临时计算新结果。到期后由独立扫描事务决定创建 Run 或跳过，再推进计划。
- 没有改变手动执行和 Worker 数据归属；计算本身不生成 Run，到期触发事务另行创建 Run/首次 Attempt。

## 旧副本升级

V3 只新增字段并将已有副本默认标记为 PENDING，保留配置版本、快照和同步 Inbox，不修改旧迁移。

后台按 Job ID 分批补算 PENDING 副本，每条独立事务，锁定最新副本后检查状态。多个 Scheduler 同时补算，或补算与配置更新竞争时，行锁及版本/状态条件防止覆盖新结果。单条旧快照格式错误记录 INVALID 后继续处理其他计划；数据库故障则保持待补算，后续扫描恢复。

参数集中于 Scheduler `application.yml` 的 `taskflow.plans`：`initialization-enabled: true`、`scan-interval: 1s`、`batch-size: 100`。补算使用独立 `plan-calculation-` 线程；既有手动执行派发使用独立 `run-dispatch-` 线程。此后台任务只扫描 PENDING 状态，不是到期触发器。

## 查询 API

内部 `GET /internal/v1/plans/selectByJobId?id=7` 继续使用控制密钥认证；原有副本响应增加 `nextFireTime`、`calculatedAt`、`calculationState`。日期通过 HTTP 以 UTC ISO 8601 表达。

管理接口新增 `GET /api/v1/job-sync/selectPlanByJobId?id=7`，由 Admin 在 SQL 事务外调用 Scheduler，无自动网络重试。沿用配置同步客户端的连接超时 2 秒、请求超时 3 秒。失败时显示真实错误，不用 Admin 的 Cron 预览或 Redis 缓存代替 Scheduler 状态。

返回 `ApiResponse.data` 示例：

```json
{
  "jobId": 7,
  "configVersion": 2,
  "deleted": false,
  "enabled": true,
  "scheduleType": "CRON",
  "cronExpression": "0 0 9 * * *",
  "timeZone": "Asia/Shanghai",
  "calculationState": "READY",
  "nextFireTime": "2026-10-26T01:00:00Z",
  "calculatedAt": "2026-10-26T00:00:00Z",
  "updatedAt": "2026-10-26T00:00:00Z",
  "automaticTriggerEnabled": true
}
```

返回的版本、时区与时间均来自 Scheduler 实际副本，可与 Admin 当前保存版本不同；不包含完整业务参数快照。缺少副本为 404 `PLAN_NOT_FOUND`，服务未配置或不可达为 503，响应缺字段或计算结果不一致为 503 `SCHEDULER_PROTOCOL_ERROR`，非法 ID 为 400。已删除任务的副本仍可查询。

详情页独立展示该结果，按副本时区格式化时间，标明调度配置版本。副本落后、PENDING、尚未收到副本或已开启自动触发的 READY 计划每两秒刷新；请求失败可以人工刷新；关闭详情会取消请求和轮询。节点触发开关与 READY 分开展示，执行结果从运行记录查询。

## 验证与下一步

实际结果见 [验证记录](plan-calculation-verification.md)。

根目录执行 `mvnw.cmd verify`，再执行 `tools/test-execution.ps1 -SkipBuild` 验证三个隔离服务和浏览器。

`PlanSyncTests` 验证持久化、查询不推进、变更重算、元数据变更保留、启停、墓碑、无下一时间、事务回滚与并发补算。`PlanCalculationTests` 验证严格晚于基准、闰日、DST 跳时和重复时刻。`PlanMigrationTests` 从 V2 实际升级到 V3，再验证旧副本一次补算，默认使用独立 H2；提供 `TASKFLOW_SCHEDULER_TEST_DB_URL/USERNAME/PASSWORD` 可使用本机新的专用空 MySQL schema `taskflow_it_plan_migration_<唯一后缀>`，按 `application-test-mysql.yml` 加载配置，禁止连接业务库。

到期扫描、同一 Job/计划时间的 Run 去重、推进和 SKIP/FIRE_ONCE 补偿已实现，见 [自动调度](automatic-scheduling.md)。关闭扫描的节点保留原计划时间，恢复时按错过策略处理。

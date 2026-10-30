# Job 配置异步同步

## 已实现的边界

Admin 是 Job 配置的权威来源；Scheduler 独立保存计划副本。新增、修改、启用、停用和软删除都会产生带版本的配置事件。保存接口只等待 Admin 本地事务提交，不等待 Scheduler HTTP 响应。

配置同步、Scheduler 下一计划时间及自动触发已完成。管理台分别显示同步确认、节点触发开关、计划时间和执行状态，接收配置不等同于任务成功。现有手动执行仍使用调用时的配置快照。计算规则见 [计划计算](plan-calculation.md)，到期事务和错过补偿见 [自动调度](automatic-scheduling.md)。

## 写入与发送

1. `JobServiceImpl` 在同一个 Admin 数据库事务内修改 `job_definition`，并通过 `IJobSyncService.enqueue` 写入 `job_sync_outbox`。任一写入失败，两者一起回滚。
2. 每个事件固定保存 `eventId`、`jobId`、`configVersion`、`deleted` 和完整配置快照。发送时不重新读取 Job；删除也写入递增版本的墓碑。
3. `job-config-sync-` 专用调度线程定期扫描 Outbox。每实例单线程逐条发送、每轮最多 20 条，无无界任务队列；多实例通过条件 UPDATE 竞争事件租约。
4. 领取、HTTP、确认是三个独立步骤。HTTP 在 SQL 事务外执行，连接等待不长期占用数据库事务，也不占用保存请求的 Tomcat 线程。
5. 只有收到匹配事件、Job、版本且 `acceptedVersion >= configVersion` 的合法响应后才记录确认。确认版本相等为 `SYNCED`，更高为 `SUPERSEDED`。

数据库提交与远程接收不原子。Scheduler 接收后响应丢失、Admin 确认写入失败或进程退出，都可能导致重复发送；不能承诺恰好发送一次。

## 协议与接收

`POST /internal/v1/plans/update`，使用现有 `X-Taskflow-Token` 控制密钥认证；`X-Request-Id` 使用事件 UUID。

```json
{
  "eventId": "20e02a11-f1a8-4228-9f51-35181854450d",
  "jobId": 7,
  "configVersion": 2,
  "deleted": false,
  "configuration": {
    "name": "订单检查",
    "description": null,
    "handlerKey": "demoSuccess",
    "payloadJson": "{\"batchSize\":100}",
    "scheduleType": "CRON",
    "cronExpression": "0 */5 * * * *",
    "timeZone": "Asia/Shanghai",
    "enabled": true,
    "timeoutSeconds": 30,
    "maxAttempts": 3,
    "retryIntervalSeconds": 5,
    "misfirePolicy": "SKIP"
  }
}
```

`payloadJson` 是保存的 JSON 对象字符串或 null，内部协议与管理接口的 `payload` 字段不同。各服务自行声明 DTO，无应用模块依赖或共享实体。

返回 HTTP 200：

```json
{
  "eventId": "20e02a11-f1a8-4228-9f51-35181854450d",
  "jobId": 7,
  "configVersion": 2,
  "acceptedVersion": 2,
  "result": "APPLIED"
}
```

Scheduler 在自己的本地事务中写入 `schedule_sync_inbox` 和 `schedule_plan`：

- Inbox 的事件 ID、`(job_id, config_version)` 分别唯一，同时保存内容指纹。
- 同一事件、版本、内容重复到达返回 `DUPLICATE`；复用标识或版本但内容不同返回 409 `PLAN_SYNC_CONFLICT`。
- 旧版本晚到返回 `SUPERSEDED` 和已保存的更高版本，不覆盖副本。即使较旧事件以前未接收过，也保留其幂等记录。
- 行锁保护同一 Job 的更新；首次插入的唯一键竞争或瞬时锁冲突只在新事务中最多重试两次。
- `deleted=true` 强制副本 `enabled=false`，保留墓碑防止旧版本重新启用。
- 不执行 Handler，不访问 Admin 数据表，不修改已经创建的 Run/Attempt 或其快照。

`GET /internal/v1/plans/selectByJobId?id=7` 使用相同控制密钥，返回副本版本、删除和启用标志、`snapshotJson`、`updatedAt`。不存在返回 404 `PLAN_NOT_FOUND`。

## 失败、重试与恢复

| 状态 | 含义 |
| --- | --- |
| PENDING | 等待首次发送，或等待退避后重试 |
| SENDING | 某个发送者持有租约，尚未确认 |
| SYNCED | Scheduler 已确认该版本 |
| SUPERSEDED | Scheduler 已确认更高版本，不表示该事件版本当前生效 |
| FAILED | 自动次数耗尽或不可重试错误，需要检查后人工重试 |

默认连接超时 2 秒、请求超时 3 秒、发送租约 30 秒；配置校验要求租约大于两项超时之和并有余量。领取时增加当前重试周期的尝试次数，最多 5 次。可重试错误采用指数退避，基础 2 秒、上限 60 秒。连接失败、超时、响应协议错误、HTTP 5xx/408/429 可重试；其他非成功状态直接失败。缺少控制密钥会进入 `FAILED/SERVICE_NOT_CONFIGURED`。

后台线程仍可能等待 HTTP，但并发有界且与用户请求隔离。批次串行处理，积压会延迟同步；吞吐量尚无基准测试。

租约过期后其他扫描者可重新领取，并使用同一个事件 ID 与快照。确认和失败更新同时检查状态、租约 token 和到期时间，旧发送者不能覆盖新领取者结果。最后一次发送失联并租约过期时记录 `FAILED/DELIVERY_UNCONFIRMED`，其含义是未确认，Scheduler 可能已经保存。

人工重试只允许最新配置的 `FAILED` 事件，沿用事件 ID 与快照，重置重试周期的尝试次数。与 Job 写入使用同一行锁串行化；不会重新创建配置版本或生成 Run。若最新版本仍在发送，返回 409。

最新同步状态直接查询 Admin 数据库，不放入 Redis 详情缓存，不依赖缓存失效才能看到确认。`SYNCED` 是历史接收确认，不能当作 Scheduler 实时健康状态或数据丢失后的重建证明。Outbox/Inbox 保留全部事件；Admin 已提供包含删除墓碑的分批对账及受控补发，匹配原事件可恢复缺失/落后的副本，见 [配置对账](config-reconciliation.md)。自动归档及整库灾难恢复不在此能力范围。

## 管理 API 与控制台

- `GET /api/v1/job-sync/selectByJobId?id=7`：最新同步状态，含已删除 Job 的墓碑。
- `POST /api/v1/job-sync/updateRetry`：请求 `{"id":7,"configVersion":2}`，只重新排队，不等待 HTTP；返回最新状态。
- 返回沿用 Admin 的 `ApiResponse`。状态包含 `eventId/jobId/configVersion/deleted/state/attempts/nextAttemptAt/leaseUntil/lastErrorCode/acknowledgedVersion/syncedAt/updatedAt`；无记录为 404，版本或状态冲突为 409。
- 保存、启停和删除响应仅确认本地配置写入；提示明确说明等待同步。
- 详情页显示同步版本和结果，等待状态每两秒刷新，关闭抽屉停止轮询；查询失败显示错误，可刷新；失败事件可人工重试，提交未确认时通过查询核实。

## 配置与迁移

参数集中在 Admin `application.yml` 的 `taskflow.job-sync`。地址和控制密钥分别复用环境变量 `TASKFLOW_SCHEDULER_URL`、`TASKFLOW_CONTROL_TOKEN`；本机密钥继续存放被忽略的 `config/taskflow-internal-local.yml`。`TASKFLOW_JOB_SYNC_ENABLED=false` 只暂停后台扫描，保存仍会写入 Outbox，重新开启后恢复发送。

Admin V3 新建 Outbox，并将升级前已有 Job 和删除墓碑回填为待同步事件。Scheduler V2 新建副本和 Inbox，V3 增加下一计划时间与计算状态，V4 增加 Run 触发来源和计划唯一键。升级时建议先启动新版 Scheduler，再启动新版 Admin；Flyway 自动执行各自增量迁移，不修改已应用的旧文件。两个服务的事务、表和 schema 互相隔离。

## 验证

```powershell
.\mvnw.cmd verify
.\tools\test-execution.ps1 -SkipBuild
```

默认测试使用独立 H2。`JobSyncTests` 覆盖事务回滚、不可变快照、真实 HTTP 客户端、退避与次数上限、错误确认、旧版本完成、租约恢复、发送竞争和非阻塞保存。`PlanSyncTests` 覆盖认证、重复/冲突、乱序、墓碑、并发和事务回滚。

实际结果见 [配置同步验证记录](config-sync-verification.md)。

三进程脚本使用隔离 H2 文件数据库并关闭延迟落盘，验证 Scheduler 离线时保存、有限重试、Admin 重启后人工补偿、真实 HTTP 同步、启停/删除、迟到事件及 Scheduler 重启。浏览器覆盖状态显示、失败恢复、重试未确认、关闭停止轮询和移动端布局。脚本的连接参数均写入每次测试独立生成的 YAML，不连接业务 MySQL。

`JobSyncMysqlMigrationTests` 是可选的真实 MySQL 升级测试，仅接受本机空 schema `taskflow_it_sync_migration_<唯一后缀>`。按 `application-test-mysql.yml` 提供 `TASKFLOW_TEST_DB_URL/USERNAME/PASSWORD` 后，运行 `mvnw.cmd -pl taskflow-admin -Dtest=JobSyncMysqlMigrationTests test`；先应用 V1/V2、写入测试旧配置和墓碑，再验证 V3 回填。必须使用新的专用空 schema，不能指向已升级的库。

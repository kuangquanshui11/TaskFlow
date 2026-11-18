# 运行与运维历史归档

我让 Admin、Scheduler 和 Worker 分别在自己的数据库内迁移历史明细。归档表与在线表使用同一数据库事务。搬迁后继续使用原有主键和唯一索引确认请求、消息与配置版本，归档不改变业务状态。当前没有永久清理规则，归档仍计入数据库容量并随所属数据库一起备份。

| 服务 | 搬迁条件及内容 | 保留在在线表的记录 |
| --- | --- | --- |
| Scheduler | Run 终态且早于截止时间，所有 Attempt 终态、执行占位已释放、取消已收尾、副作用核对已有结论，已有派发失败和已关联的死信均已结案。搬迁 Run 配置快照、Attempt 结果正文、取消原因、核对证据、派发消息正文和时间线事件 | Run ID、状态和列表字段、手动请求键及指纹、Cron 的 `(job_id, scheduled_at)`、Attempt 身份及租约、取消及核对回执、Outbox 路由和处置状态 |
| Worker | 终态和 Scheduler 成功确认时间均早于截止时间。搬迁派发正文、结果正文及执行事件 | Inbox 的 Attempt ID、接收指纹、运行身份、状态和时间字段。上报耗尽、仅收到过期拒绝及尚未确认的执行继续在线保留 |
| Admin | 搬迁已确认操作及无手动触发请求键的已拒绝审计，已结束的对账明细，以及有更新版本、已确认或已被取代的非删除同步事件正文 | 手动触发审计保留原请求键、操作者和 Run 绑定。对账保留批次、轮次和修复回执。保留配置事件标识、现行版本原始快照及删除墓碑 |

Scheduler 历史 Run 仍可通过原 Run ID 或请求键定位。最近运行列表、统计和查询窗口保持原口径。Attempt 时间线合并在线和归档事件，按事件 ID 排序并沿用原读限额。归档后到达的取消补报等新证据仍可追加并读取。Worker 的执行时间线仍依赖原 Worker 可达。独立 Handler 文件日志沿用其文件保留期限。Admin 的审计查询和对账按原权限和参数读取在线与归档数据。现行配置同步、补发和维护恢复继续使用原事件。

## 部署与操作

1. 先备份三个服务各自的 schema。将同一 schema 的所有服务实例升级到支持归档读取的版本，保持归档为 `off`。Flyway 为 Scheduler、Worker、Admin 分别应用 V19、V7、V11。
2. 对选定服务以 `preview` 模式启动。在日志中查找 `History archive batch preview=true`。数字是本批可处理的条数，受批次上限约束。
3. 核对预览后对同一服务以 `once` 模式启动。每次启动搬迁一批。在日志中核对 `preview=false` 和记录数，再用已知 Run ID、审计或对账标识确认历史可读。同一批出现 SQL 错误时整批回滚，排除失败原因后可重跑。预览后的记录可能变化，实际执行会重新选择。
4. 连续运行时为选定实例配置 `scheduled`。第一批在一个间隔后执行。每个 schema 的归档锁序列化同库多实例运行。调整配置后重启生效。执行过归档的 schema 若要恢复旧版二进制，先恢复兼容的数据备份，旧版只认识原在线正文。

本地预览示例。已有本地服务时可以只重启所选服务，其他实例分别配置。

```powershell
$env:TASKFLOW_HISTORY_ARCHIVE_MODE = 'preview'
.\tools\services.ps1 -Action restart -Service scheduler
Remove-Item Env:TASKFLOW_HISTORY_ARCHIVE_MODE
```

| 环境变量 | 默认值 | 约束 |
| --- | --- | --- |
| `TASKFLOW_HISTORY_ARCHIVE_MODE` | `off` | `off`、`preview`、`once`、`scheduled` |
| `TASKFLOW_HISTORY_ARCHIVE_RETENTION_DAYS` | `90` | 7–3650 天，以服务时钟计算 |
| `TASKFLOW_HISTORY_ARCHIVE_BATCH_SIZE` | `20` | 1–100 个主记录，Admin 对每类分别限量 |
| `TASKFLOW_HISTORY_ARCHIVE_INTERVAL` | `1h` | 至少 30 秒，仅定时运行使用 |

我保留 Scheduler 计划同步 Inbox、Worker 旧节点会话和各类去重回执，直到确定这些身份的重放期限。缩短保留期只会更早归档明细。归档操作不改变旧消息、手动请求键或配置事件的认定规则。

## 2026-11-18 验证

环境为 Windows、Java 21、隔离 H2 与独立的 MySQL 8.0.34 临时实例。

| 命令 | 结果和证据 |
| --- | --- |
| `.\mvnw.cmd -B -o verify` | 至 Scheduler 的各模块通过。Worker 的归档用例查出测试造数改变了结果上报耗时，修正测试时间戳后从 Worker 和订单模块继续验证。证据为 `.tools/history-archive-verify.log` |
| `.\mvnw.cmd -B -o -pl taskflow-worker,examples/taskflow-order-service verify` | 149 项通过、43 项条件跳过。覆盖原消息进入 AMQP 消费服务后重新 ACK、Handler 执行一次、归档后时间线可读和未确认记录保留。证据为 `.tools/history-archive-resume-verify.log` |
| `.\tools\test-run-state-mysql.ps1 -Port 13427` | 25 项通过。覆盖真实 MySQL 归档、取消回执和副作用核对重放以及状态竞争。证据为 `.tools/run-state-mysql/20261118-091722-559/tests.log` |

本轮 MQ 重投验证使用真实消费服务和模拟 Channel。Admin 和 Worker 的归档查询及移动使用 H2 集成测试。真实 Broker 上归档前后的重投和生产数据量下的归档耗时尚未实测。

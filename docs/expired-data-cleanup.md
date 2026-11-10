# 过期库存缓存投递历史清理

## 数据归属与保留

我把清理范围固定为订单服务的 `stock_outbox_history`。订单服务成功刷新 Redis 后，在同一本地事务中记下事件 ID、SKU 和成功时间，再移除待投递行。刷新失败继续按原策略重试，耗尽后保留待投递行供对账。V4 迁移从空历史表开始记录新发生的成功投递。启用库存缓存且刷新成功时才会产生历史。

我按 `published_at < cutoff` 选择数据。订单服务用首次绑定 Run 请求时的 UTC 时间减去保留天数，每天按 24 小时计。保留天数为 90–3650。等于截止时间的记录归入下一轮。任务时区决定每天的触发时刻。

## 执行与重试

预览读取最多一批候选，`hasMore=true` 表示查询时还有超出该批的记录。删除以 `(published_at, id)` 排序，从最早的候选开始，每批最多 500 条、每 Run 最多 10 批。Worker 在每次请求前检查租约和剩余时间，按 Run 派生业务请求键。订单服务先绑定截止时间和参数，再逐批锁定候选行。删除与批次结果在同一事务提交。同一 Run 的 Attempt 从第一批重新请求，已提交批次返回原结果，随后继续处理未提交批次。并发 Run 按候选行锁协调。

HTTP POST 发送一次。连接失败、408、429 和 5xx 可按 Scheduler 的 Attempt 上限重试。参数、鉴权及顺序冲突需要修正后重新发起。Worker 超时后，已经到达订单服务的批次仍可能提交。Run 摘要显示模式、截止时间、处理数量及是否还有候选。到达批数上限时若仍有候选，后续 Run 可继续处理。业务请求与批次结果独立保留，用于重复请求和审计。 [内部协议](api.md#过期库存缓存投递历史清理-api-v1)

## 本机接入

1. 在被 Git 忽略的 `config/taskflow-order-cleanup-local.yml` 下配置 `order.cleanup.enabled: true` 和 `order.cleanup.token`。在 `config/taskflow-worker-cleanup-local.yml` 下配置 `taskflow.expired-data-cleanup.enabled: true` 和 `taskflow.expired-data-cleanup.token`。两个服务使用同一个专用随机令牌，独立于订单 API 和订单 Worker 令牌。也可使用 `TASKFLOW_EXPIRED_DATA_CLEANUP_SOURCE_ENABLED`、`TASKFLOW_EXPIRED_DATA_CLEANUP_HANDLER_ENABLED` 和 `TASKFLOW_EXPIRED_DATA_CLEANUP_TOKEN`。
2. 确认订单相关的在途工作已结束并备份订单库，执行 `.\mvnw.cmd verify`，依次更新订单服务与 Worker，核实订单库迁移到 V4。确认 Worker 在线、可派发且上报 `expiredDataCleanup`。核对 Admin 导入的本机配置，保留原有处理器并追加缺少的标识，重启 Admin 后通过 `/api/v1/job-handlers/select` 确认生效。
3. 更新前端发行包后，在「新建任务」选择「清理过期数据」，保持停用和 `dryRun: true`。手动触发并在运行详情核对候选数量。确认清理范围后，将任务改为 `dryRun: false`，仍以停用状态手动执行。检查删除数量、截止时间以及是否还有候选。
4. 手动验证通过后启用计划，默认 Asia/Shanghai 每天 03:00。检查计划副本、下次触发时间和首个定时 Run。遇到处理失败，按 Run 摘要核对已提交批次，确认原因后再执行。

## 验证命令

```powershell
.\mvnw.cmd verify
.\tools\test-order-mysql.ps1 -IncludeCleanup
Push-Location taskflow-web
npm.cmd test
npm.cmd run build
Pop-Location
```

MySQL 专项使用独立临时库。验收覆盖鉴权、预览只读、90 天边界、批量上限、相同和不同 Run 并发、事务回滚、响应重放、缓存失败和待投递记录保留。

已构建服务 JAR 时，可使用现有三节点集群运行隔离的清理专项。脚本新建随机 vhost、临时回环 MySQL 和两个独立 schema，依次验证模块测试、实际 MQ 预览、提交后响应丢失的重试与加速 Cron。外部集群配置见 [可靠派发](reliable-dispatch.md#接入已有外部集群)。

```powershell
.\tools\test-mq.ps1 -CleanupOnly -SkipBuild -SkipFailover -ClusterSettingsPath .tools/mq-cluster-settings.json -AllowLegacyBroker
```

`-AllowLegacyBroker` 用于本机接入的 3.8.34 集群，清理专项覆盖正常派发和业务重放。新版 Broker 可去掉该选项。脚本成功后回收本轮创建的 vhost，失败时保留供排查。

## 2026-11-09 验收记录

环境为 Windows、Java 21、H2、临时回环 MySQL 8.0.34（REPEATABLE READ）与 Chrome。浏览器使用 Nginx、Gateway、Keycloak 和隔离的临时 Redis；业务进程使用隔离数据库和 HTTP 派发。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `mvnw.cmd -B -o verify` | 根构建成功，370 项通过，32 项按条件跳过 | `.tools/cleanup-root-verify.log` |
| `mvnw.cmd -B -o -pl examples/taskflow-order-service -Dtest=CleanupTests test` | 5 项清理专项通过 | `.tools/cleanup-order-tests.log` |
| `tools/test-order-mysql.ps1 -IncludeCleanup` | 13 项通过，含 5 项清理专项 | `.tools/order-mysql/20261109-182325-084/tests.log` |
| 在 `taskflow-web` 执行 `npm.cmd test` 与 `npm.cmd run build` | 29 项通过，前端构建成功 | 终端输出 |
| `.tools/run-cleanup-preview.ps1` | 控制台创建停用模板。预览删除 0 条，手动和定时各删除 1 条过期样例。数据库保留近期真实 Redis 刷新产生的成功记录，并保存两批删除结果。测试计划随后恢复为每天 03:00，计划状态为 `READY` | `.tools/cleanup-preview/20261109-182342-181/evidence.json`、`database-evidence.txt`、`template.png` |

该轮定时 Run `a4af7c13-a033-4333-83b0-27c413f2bd3a` 于 18:25（Asia/Shanghai）通过加速计划触发，使用隔离 HTTP 派发。

## 2026-11-10 本机接入与 RabbitMQ 验收

环境为 Windows、Java 21、Chrome、MySQL 8.0.34 和 RabbitMQ 3.8.34 三节点。时间采用 Asia/Shanghai。常驻环境用于浏览器接入和只读预览，删除与定时测试使用独立 MySQL schema 和随机 vhost。

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| 订单库升级与处理器接入 | 确认订单相关在途 Run 为 0，完成订单库备份及 SHA-256 校验，订单服务升级后 Flyway 由 V3 到 V4。重启 Admin 加载已有白名单，确认 Worker 在线且可通过 RabbitMQ 派发 `expiredDataCleanup` | `.tools/cleanup-local/20261110-095502-738/migration.json`、`backup.sha256` |
| 本机浏览器只读预览 | 创建停用的任务 11，保留 `dryRun: true` 和 90 天保留期。手动 Run `d5c1357e-f0b2-40e8-a835-6e98274910d6` 成功，候选数和删除数均为 0，历史和删除批次计数前后均为 0 | 同目录的 `job.json`、`run.json`、`template.png`、`run.png` |
| 隔离 MySQL 模块测试 | 13 项通过，包含 5 项清理测试。验证固定截止时间、等于截止时间的保留规则和批次重放 | `.tools/order-mysql/20261110-100156-791/tests.log` |
| 隔离 RabbitMQ 派发 | 3 项检查通过。预览保留全部数据，模拟删除提交后响应丢失时，同一 Run 的第二个 Attempt 返回首个批次结果，删除批次计数仍为 1。加速 Cron 产生新的成功 Run，删除第二条过期记录，保留临界期内记录、近期记录和待投递事件 | `.tools/order-flow/20261110-100218-067/evidence.json`、`.tools/mq-e2e/taskflow_it_f7cebe9bb16b44d9a3062ecc79f912b6/report.json` |

本机任务配置为每天 03:00 并处于停用状态，库存缓存配置为关闭。定时验收使用独立环境中的加速 Cron，覆盖正常 RabbitMQ 派发和业务重放。每日 03:00 的实际轮次与 Broker 故障切换不在本轮验收范围内。

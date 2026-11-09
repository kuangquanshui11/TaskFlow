# 过期库存缓存投递历史清理

## 数据归属与保留

我把清理范围固定为订单服务的 `stock_outbox_history`。订单服务成功刷新 Redis 后，在同一本地事务中记下事件 ID、SKU 和成功时间，再移除待投递行。刷新失败继续按原策略重试，耗尽后保留待投递行供对账。V4 迁移从空历史表开始记录新发生的成功投递。启用库存缓存且刷新成功时才会产生历史。

我按 `published_at < cutoff` 选择数据。订单服务用首次绑定 Run 请求时的 UTC 时间减去保留天数，每天按 24 小时计。保留天数为 90–3650。等于截止时间的记录归入下一轮。任务时区决定每天的触发时刻。

## 执行与重试

预览读取最多一批候选，`hasMore=true` 表示查询时还有超出该批的记录。删除以 `(published_at, id)` 排序，从最早的候选开始，每批最多 500 条、每 Run 最多 10 批。Worker 在每次请求前检查租约和剩余时间，按 Run 派生业务请求键。订单服务先绑定截止时间和参数，再逐批锁定候选行。删除与批次结果在同一事务提交。同一 Run 的 Attempt 从第一批重新请求，已提交批次返回原结果，随后继续处理未提交批次。并发 Run 按候选行锁协调。

HTTP POST 发送一次。连接失败、408、429 和 5xx 可按 Scheduler 的 Attempt 上限重试。参数、鉴权及顺序冲突需要修正后重新发起。Worker 超时后，已经到达订单服务的批次仍可能提交。Run 摘要显示模式、截止时间、处理数量及是否还有候选。到达批数上限时若仍有候选，后续 Run 可继续处理。业务请求与批次结果独立保留，用于重复请求和审计。 [内部协议](api.md#过期库存缓存投递历史清理-api-v1)

## 本机接入

1. 在被 Git 忽略的 `config/taskflow-order-cleanup-local.yml` 下配置 `order.cleanup.enabled: true` 和 `order.cleanup.token`。在 `config/taskflow-worker-cleanup-local.yml` 下配置 `taskflow.expired-data-cleanup.enabled: true` 和 `taskflow.expired-data-cleanup.token`。两个服务使用同一个专用随机令牌，独立于订单 API 和订单 Worker 令牌。也可使用 `TASKFLOW_EXPIRED_DATA_CLEANUP_SOURCE_ENABLED`、`TASKFLOW_EXPIRED_DATA_CLEANUP_HANDLER_ENABLED` 和 `TASKFLOW_EXPIRED_DATA_CLEANUP_TOKEN`。
2. 确认在途工作已结束，执行 `.\mvnw.cmd verify`，依次更新订单服务与 Worker。确认 Worker 在线、可派发且上报 `expiredDataCleanup`，在 Admin 的有效 `taskflow.jobs.allowed-handler-keys` 中追加此标识并重启 Admin。保留其他业务处理器的现有配置。
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

## 2026-11-09 验收记录

环境为 Windows、Java 21、H2、临时回环 MySQL 8.0.34（REPEATABLE READ）与 Chrome。浏览器使用 Nginx、Gateway、Keycloak 和隔离的临时 Redis；业务进程使用隔离数据库和 HTTP 派发。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `mvnw.cmd -B -o verify` | 根构建成功，370 项通过，32 项按条件跳过 | `.tools/cleanup-root-verify.log` |
| `mvnw.cmd -B -o -pl examples/taskflow-order-service -Dtest=CleanupTests test` | 5 项清理专项通过 | `.tools/cleanup-order-tests.log` |
| `tools/test-order-mysql.ps1 -IncludeCleanup` | 13 项通过，含 5 项清理专项 | `.tools/order-mysql/20261109-182325-084/tests.log` |
| 在 `taskflow-web` 执行 `npm.cmd test` 与 `npm.cmd run build` | 29 项通过，前端构建成功 | 终端输出 |
| `.tools/run-cleanup-preview.ps1` | 控制台创建停用模板。预览删除 0 条，手动和定时各删除 1 条过期样例。数据库保留近期真实 Redis 刷新产生的成功记录，并保存两批删除结果。测试计划随后恢复为每天 03:00，计划状态为 `READY` | `.tools/cleanup-preview/20261109-182342-181/evidence.json`、`database-evidence.txt`、`template.png` |

实际定时 Run `a4af7c13-a033-4333-83b0-27c413f2bd3a` 于 18:25（Asia/Shanghai）通过加速计划触发。常驻环境的 RabbitMQ 派发及每日 03:00 的实际轮次待验收。

# 下一计划时间验证记录

验证日期：2026-10-26。实现边界见 [计划计算](plan-calculation.md)。验证未升级业务实例。

## 后端与升级

- 根目录 `mvnw.cmd verify` 通过：89 项可运行测试通过，5 项带外部环境条件的测试按默认配置跳过。
- `PlanCalculationTests` 覆盖严格晚于基准、闰日、纽约 DST 不存在时刻及重复时刻。
- `PlanSyncTests` 新增持久化和真实查询、时区与 Cron 重算、元数据修改保留、启停/手动/删除/无未来日期、重复与旧版本不推进、回滚和并发补算验证。
- 对旧快照损坏和字面 null 补充验证，均标记 INVALID，并继续补算其他 Job；补充后针对 Scheduler 执行 verify 并重新生成 JAR，通过。
- `JobSyncTests` 新增 Admin 到 Scheduler 查询的真实 HTTP 客户端验证：SQL 事务外调用、返回实际版本与时间、404/503/格式错误，不虚构计划结果。
- `PlanMigrationTests` 默认使用独立 H2 从 V2 升级到 V3，验证旧副本的 PENDING→计算状态；另在独立 MySQL 8.0.34 实例的新 `taskflow_it_plan_migration_` schema 执行同一升级测试通过。覆盖启用 CRON、停用、手动、删除与无下一时间，不增加配置版本、Inbox 或 Run。

## 独立进程与浏览器

运行 `tools/test-execution.ps1 -SkipBuild`，使用三个独立 H2 文件数据库与随机内部密钥，不写业务 MySQL/Redis。

- 29 项进程验收通过，含原手动执行与异步同步回归。
- 新增真实链路：保存启用的 Cron → 异步同步 → Admin 查询 Scheduler 持久化时间，并与同一基准下的 Cron 预览对照。
- Scheduler 重启前后的 nextFireTime 与 calculatedAt 相同；停用后清空时间，计算过程没有创建自动 Run。
- 前端单元测试 9 项、Vite 生产构建通过。
- Playwright 12 项全部通过。新增页面验证 Cron、时区修改，启停、删除、人工刷新、查询失败恢复、较旧版本说明、等待计算、关闭详情停止请求和 375px 布局。
- 已查看 `plan-mobile.png`：下一计划时间、Asia/Shanghai 时区、调度配置版本、刷新操作和“到点不会自动执行”说明可见，无横向溢出。

日志保存于被忽略的 `.tools/verification/plan-*.log`；进程和浏览器证据保存于 `.tools/execution-e2e/20261026-194803-550/`，包含 `report.json`、服务日志和截图，不打包测试数据库或密钥。

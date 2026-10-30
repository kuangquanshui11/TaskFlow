# 异步配置同步验证记录

验证日期：2026-10-26。范围为配置同步，不包含 Cron 自动触发。实现和协议见 [配置同步](config-sync.md)。

## 后端与数据库

- 根目录 `mvnw.cmd verify`：Admin、Scheduler、Worker 默认可运行的 78 项测试通过；真实 Redis 连接、真实 Redis 缓存与专用 MySQL 升级测试默认按条件跳过。
- 新增 `JobSyncTests` 8 项、`PlanSyncTests` 5 项，覆盖配置/Outbox 原子提交、快照、退避、次数上限、错误确认、租约恢复、旧领取者、并发发送、非阻塞保存、接收幂等、乱序、墓碑与回滚。
- 在独立 MySQL 8.0.34 测试实例、`taskflow_it_` 专用 schema 运行原 `JobApiTests` 16 项通过，Flyway V1/V2/V3 全部成功；未连接业务 schema。
- 在新的专用空 schema 运行 `JobSyncMysqlMigrationTests` 1 项通过：V2 旧定时任务、嵌套中文 JSON 和删除墓碑升级到 V3，回填快照可被实际 DTO 读取，后续修改不改变事件快照。

## 三个独立进程

运行 `tools/test-execution.ps1 -SkipBuild`，每服务使用独立 H2 文件数据库、独立 YAML 与随机内部密钥。测试过程中不写业务 MySQL、Redis 或业务服务。

23 项进程验收通过，包含原有手动执行闭环，以及新增配置同步检查：

1. Scheduler 离线时配置保存成功，不等待远程服务。
2. 三次测试发送后进入 FAILED，不无限重试。
3. Admin 重启保留失败事件 ID、版本和状态，人工重试沿用同一事件。
4. Scheduler 恢复后通过真实 HTTP 保存副本，Admin 收到对应版本确认。
5. 启用、停用、删除分别推进版本并同步；删除后的状态仍可查询。
6. 删除后迟到的旧事件返回最新版本，不复活计划。
7. Scheduler 重启保留副本和删除墓碑。

H2 的默认延迟落盘会使强制停止后的最近提交在测试中丢失；隔离测试 YAML 设置 `WRITE_DELAY=0`，让进程强制退出验收使用立即落盘。该设置只作用于隔离测试环境。

## 前端

- Vite 生产构建通过，前端单元测试 9 项通过。
- 真实 Nginx → Admin → Scheduler → Worker 环境下，Playwright 10 项通过，含原控制台/执行用例与新增同步用例。
- 新增用例验证真实同步状态、查询失败后刷新、重试请求未确认时不显示成功、关闭抽屉停止轮询、375px 页面不溢出。
- 已人工查看移动端截图，状态、版本、确认时间、刷新按钮及“Cron 自动触发尚未接入”说明可见。

本机证据保存在被忽略的 `.tools/verification/job-sync-*.log` 和 `.tools/execution-e2e/20261026-192738-418/`，后者包含 `report.json`、服务日志和 `sync-mobile.png`；测试数据及密钥不打包进业务 JAR。

验证未升级业务实例。部署时先升级 Scheduler，再升级 Admin，并更新 Nginx 静态构建；Flyway 各自应用新增迁移。

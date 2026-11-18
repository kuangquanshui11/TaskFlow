# 配置对账与受控修复验证

本记录覆盖按 Job ID 的手工/自动双向全量对账、受控补发和维护恢复。全局配置事件游标的增量追赶（P03）尚未实现，以下历史结果和文件名中的 `p03` 均不能作为其验收证据。剩余场景见 [P03 设计与验收范围](config-reconciliation.md#配置事件增量追赶p03未实现)。

## 2026-11-16 自动全量对账

Java 21，Maven 3.10.0，Admin 与 Scheduler 独立 H2 库。执行根目录 Maven `verify`，Admin 的 V4 → V5 迁移测试 1 项、对账测试 11 项和 Scheduler 配置协议测试 17 项均通过。新用例覆盖双向分页上界、墓碑、实际快照指纹、缺失/落后/超前/同版本冲突/孤立，以及远端不可用和退避续扫。完整构建在示例 `taskflow-order-service` 的 `CleanupTests.exhaustedDeliveryHasDiagnosisAndConcurrentCompensationReplaysOneReceipt` 遇到 `NoSuchElementException`。本地证据为 `.tools/p03-automatic-verify.log`。

控制台 `npm.cmd run build` 成功，`npm.cmd test` 通过 29 项。本轮环境使用 H2 和本机 HTTP，真实 MySQL 与 cloud 注册发现仍待验证。

## 2026-11-16 双丢失恢复演练

Java 21，使用最终服务 JAR、新建的独立 H2 文件库、本机 HTTP 派发、Gateway、Keycloak、Redis 和 Nginx。执行以下命令：

```powershell
.\tools\test-execution.ps1 -SkipBuild -IncludeConfigReconciliation -IncludeConfigRecovery -IdentityPort 19290
```

退出码 0，报告 67 项断言全部通过，另有 5 项 Playwright 测试通过。演练清空隔离 Scheduler 的计划副本和 Inbox，保留原 Run 和 Admin 的权威数据。恢复期间 Job 写入和常规配置接收均被拒绝。同一恢复批次可重放，墓碑和 Cron 计划通过多个批次重建。重建后启动的双向轮次差异为零。保持 Cron 暂停时重放旧启用事件，确认墓碑有效。重新启用 Cron 后，Worker 完成了恢复时刻以后的新 Run。浏览器覆盖配置对账、补发、复查、移动端和只读权限。根据桌面及移动端截图调整停用时的状态文案后，前端重新构建并通过 29 项单测。

根目录 Maven `verify` 完成六个核心服务的测试及打包，Scheduler 配置协议测试 18 项通过。订单示例的库存补偿测试在根构建中再次报告 `NoSuchElementException`。单独重跑该项通过。用 `-rf :taskflow-order-service verify` 续跑整个示例模块，通过 46 项并按环境条件跳过 40 项。

本地证据位于 `.tools/execution-e2e/20261116-100404-920/report.json`、`.tools/p03-recovery-process.log`、`.tools/p03-recovery-verify.log` 和 `.tools/p03-order-verify.log`。恢复演练使用本机 H2、HTTP 和静态路由，真实 MySQL、RabbitMQ 及 Eureka 注册发现下的恢复留待环境专项。

## 环境与隔离

日期：2026-10-30（Asia/Shanghai）。环境：Windows、PowerShell 7.6.5、Temurin JDK 21.0.12.1、Spring Boot 4.1.1，使用项目 Maven Wrapper。

我们使用各服务独立 H2 内存数据库执行服务测试，Admin 的真实 V4 DDL另由 `ReconciliationMigrationTests` 执行。进程验收使用 `.tools/execution-e2e/<时间戳>` 下的新建 H2 文件库，独立 Keycloak realm、会话 Redis、限流 Redis、Gateway、Admin、Scheduler、Worker。业务 MySQL、现有身份库与运行服务不参与故障注入。

## 完整构建

```powershell
.\mvnw.cmd verify
```

结果：2026-10-30 19:37 完成，`BUILD SUCCESS`，六个服务独立打包成功。共发现 279 项测试，实际通过 271 项，按环境条件跳过 8 项，失败/错误为 0。模块统计为 Registry 1、Config Server 1、Gateway 10（跳过 1）、Admin 92（跳过 6）、Scheduler 109、Worker 66（跳过 1）。原始输出保存在本机 `.codex-reconciliation-final-verify.log`，不提交构建日志。

与本能力直接相关的测试为 Admin 对账 10 项、真实 V4 DDL 1 项、Scheduler 配置同步及恢复 16 项，均通过。另有 6 项 Admin 权限回归验证 VIEWER 拒绝对账/修复、OPERATOR 可访问相应动作且非法输入返回 400。

| 验证范围 | 证据 |
| --- | --- |
| 当前事实与历史确认分离 | 历史 SYNCED 的 Job 仍能被判为 MISSING；比较 MATCH/BEHIND/AHEAD/CONFLICT，包含已删除配置 |
| 失败不误判缺失 | 503、错误类型、返回未知 ID、缺字段均记录 UNAVAILABLE；禁止修复 |
| 有界扫描与幂等 | 单批/游标分页、相同 UUID 不发起第二次 HTTP、同 UUID 不同参数冲突、并发扫描受持久化闸门约束 |
| 网络不占 SQL 事务 | 客户端检查事务外调用；阻塞查询期间 Job 写入仍可完成 |
| 结果租约 | 超时批次显示 EXPIRED，不提交迟到观察，也不解除其他批次窗口 |
| 受控排队 | 复用 eventId/配置版本/快照；同一检查只消费一次；同修复 UUID 并发只保存一条记录 |
| 过时观察保护 | 对账后配置变化返回 STALE；超过有效期返回 EXPIRED；不重排原事件 |
| 事务与发送租约 | 审计保存失败回滚排队；不重置已有 SENDING 租约、token 或尝试计数 |
| Inbox 留存恢复 | 缺失/落后副本重放原事件恢复；重建失败后原 Inbox 留存且可再次恢复；内容冲突仍拒绝 |
| 删除恢复下界 | Inbox 记录较高墓碑时，旧事件不能重建缺失副本；最高版本墓碑恢复后仍拒绝旧版本覆盖 |
| 查询与权限 | 实际 Admin HTTP 查询批次/修复；Scheduler 批量接口认证、墓碑返回及缺失 ID 省略 |

## 独立进程故障验收

复现命令：

```powershell
.\tools\test-execution.ps1 -SkipBuild -SkipBrowser -IncludeConfigReconciliation -IdentityPort 19290
```

测试前提是本机已准备脚本使用的 Keycloak、Redis 及 Node.js；脚本从本次生成的 YAML 读取测试连接参数。默认身份服务端口 19190 已被占用，因此本次改用 19290。脚本只终止自己创建的进程。

结果：最终 JAR 的进程验收退出码 0，报告中 57 项检查全部 PASS。完整证据位于本机 `.tools/execution-e2e/20261030-193745-808/report.json`，执行输出为 `.codex-reconciliation-final-process.log`；测试目录包含临时身份配置和凭据，仅本机保留，不纳入 Git。测试使用 HTTP 派发模式，脚本沿用的部分旧提示包含“MQ”字样，不作为本次 RabbitMQ 验收证据。

故障步骤：等待删除版本同步为 SYNCED，停止隔离 Scheduler，只删除该测试 Job 的 `schedule_plan` 行，保留 Inbox，再启动 Scheduler。此时 Admin 历史确认仍为 SYNCED。随后尝试重放旧启用事件、调用对账、提交受控修复、等待原事件同步，最后重启 Admin 查询与重放同一修复请求。完整脚本还执行原有登录/会话/权限、三服务执行、重启恢复、Cron 与 Redis 故障回归。

配置故障窗口检查通过，原始证据包含：

```text
PASS Historical sync remains successful after Scheduler replica loss
PASS Surviving highest Inbox version blocks stale re-enable while replica is missing
PASS Real version query finds missing deletion tombstone despite historical confirmation
PASS Scan request replay returns persisted observation
PASS Controlled repair queues original event through Admin Outbox
PASS Surviving Inbox allows original event to rebuild tombstone without re-enabling Job
PASS Restart preserves repair audit and idempotency
```

## 未验证范围

本次未执行真实 MySQL V4 升级与 MySQL 锁竞争专项、cloud 注册/熔断真实集群、RabbitMQ 通道、浏览器界面或大规模吞吐基准。默认构建跳过的条件测试不作为已验收结果。`JobSyncMysqlMigrationTests` 已扩展为同时检查 V4 闸门，仍需专用空 MySQL schema 才执行；H2 的成功不能替代真实 MySQL 验收。

本能力不验证未知孤立副本、任意配置字段损坏或整库同时丢失副本与 Inbox 的恢复，边界和关闭自动触发的操作要求见 [设计](config-reconciliation.md)。

## 控制台复查与 Cron 恢复

日期为 2026-11-10（Asia/Shanghai）。环境为 Windows、PowerShell、Java 21、Chrome、独立 Keycloak 和会话及限流 Redis。控制台走 Nginx、Gateway、Admin。三个业务服务使用独立 H2 文件库，Scheduler 通过 HTTP 派发到 Worker。

```powershell
.\mvnw.cmd -B -o verify
.\tools\test-execution.ps1 -SkipBuild -IncludeConfigReconciliation -IdentityPort 19290
```

| 验证 | 结果 |
| --- | --- |
| Maven 全量构建 | 构建成功，370 项通过，32 项条件跳过 |
| 前端 | 29 项单元测试通过，生产构建成功 |
| 隔离脚本和浏览器 | 59 项脚本检查通过，5 个 Playwright 用例通过 |

脚本创建启用的测试 Cron 任务，等待 Admin 记录 `SYNCED`，停止隔离 Scheduler 并删除该任务的一行 `schedule_plan`，保留 Inbox 后重启。浏览器显示 `MISSING`，并确认 Admin 版本为 v1。补发在服务端受理后模拟响应丢失，显式重试沿用同一请求 ID。随后查询原批次并核实补发记录的操作者与原事件，发起新批次看到 `MATCH`、两侧均为 v1 且已启用。复查结束后，Scheduler 新建了 `CRON` 类型 Run，由 Worker 执行到 `SUCCEEDED`。

浏览器另检查了下一批使用返回的游标、查询不可用时不开放补发、任务详情同步状态与只读权限。真实故障证据保存在 `.tools/execution-e2e/20261110-093059-224/`，其中 `report.json` 记录断言，`reconciliation-missing.png`、`reconciliation-mobile.png` 和 `reconciliation-restored.png` 记录页面。终端输出保存在 `.tools/config-reconciliation-console-e2e-final.log` 与 `.tools/config-reconciliation-console-verify.log`。本轮故障注入未使用 MySQL、RabbitMQ 或 cloud 注册发现。

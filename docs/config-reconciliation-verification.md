# 配置对账与受控修复验证

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

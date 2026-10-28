# Run / Attempt 状态并发修复验证

日期：2026-10-27。范围仅为 Scheduler 状态回退问题及同一读取方式导致的并发重复回调冲突。采用 MySQL 行锁与条件更新，不增加 Redis 依赖、不修改服务间 API 或数据库结构。

## 修复内容

- `RunDataMapper.lockAttempt()` 使用 `SELECT ... FOR UPDATE`。状态事务先锁 Run，再锁 Attempt，取得当前状态；首次普通读取仅查找不可变 runId，不用于后续状态判断。
- HTTP 接收确认与 MQ 发布确认共用 `confirmAccepted()`。检查当前 Attempt、消息 leaseVersion、允许的状态与租约有效期，已终结的记录直接保留。
- Attempt 接收、终态写入及 Run 状态更新均返回是否更新成功。Attempt SQL 保护身份、尝试号、租约版本与状态；Run SQL 保护原状态和当前 Attempt，禁止终态回退。接收确认额外检查租约有效期。
- Attempt 转换未成功时不推进 Run；Run 更新失败时回滚本地事务。终态、重试 Attempt、新 Outbox 及旧 Outbox 取消一起提交或回滚。
- Worker HTTP 调用仍在事务外。本地派发确认事务的异常不进入远程拒绝分类，保留原 Attempt、原租约供后续扫描处理。
- MQ 的锁顺序为 Run → Attempt → Outbox；Relay 只锁 Outbox，不反向获取业务记录锁。没有在持有数据库锁时等待 Worker 或 Broker。

变更集中在 `taskflow-scheduler` 的 `execution/service/impl/RunServiceImpl.java`、`execution/mapper/RunDataMapper.java`。协议边界见 [执行协议](execution-protocol.md)，对外字段和路径继续见 [API](api.md)。

## 修复前验证

先加入 `RunStateConcurrencyTests` 的前 8 项并发测试，在尚未修改业务实现时运行真实 MySQL 8.0.34、REPEATABLE READ：

| 场景 | 原实现结果 |
| --- | --- |
| HTTP 确认与成功结果竞争 | Run 被改回 RUNNING，断言失败 |
| HTTP 确认与失败终态竞争 | Run 被改回 RUNNING，断言失败 |
| MQ 确认与成功结果竞争 | Run 被改回 RUNNING，断言失败 |
| MQ 确认与失败终态竞争 | Run 被改回 RUNNING，断言失败 |
| HTTP / MQ 与可重试失败竞争 | 2 项通过 |
| 并发重复成功 / 可重试失败结果 | 2 项抛出不应出现的 STALE_ATTEMPT |

统计：8 项，4 个断言失败、2 个错误、2 项通过。先验证测试能够发现原缺陷，再修改实现。

证据：`.tools/run-state-mysql-before-tests.txt`、`.tools/run-state-mysql/20261027-145615-579/tests.log`。

## 修复后验证

执行：

```powershell
.\tools\test-run-state-mysql.ps1
.\mvnw.cmd -pl taskflow-scheduler verify
```

| 验证 | 结果 |
| --- | --- |
| MySQL 8.0.34 / REPEATABLE READ | 15 项通过，0 失败、0 错误、0 跳过 |
| Scheduler 完整 verify，隔离 H2 | 73 项通过，0 失败、0 错误、0 跳过 |
| Scheduler 可执行 JAR | 重新打包成功 |
| PowerShell 验收脚本语法 | 通过 |

新增的 15 项包含：

1. HTTP / MQ 确认与成功、失败、可重试失败结果竞争，共 6 项。通过锁和线程屏障安排“回调持有 Run 锁 → 确认读取旧快照 → 回调提交 → 确认继续”的顺序，无需依赖随机竞争。
2. 并发相同成功结果与相同可重试失败结果，共 2 项。两次调用都正常确认，不产生重复重试 Attempt。
3. 7 项 SQL 与事务保护：接收更新 0 行不推进 Run、过期确认不标记运行、Run 确认更新失败回滚并恢复原 Attempt、错误租约与冲突结果拒绝及相同迟到结果幂等、旧状态/旧租约/终态的 SQL 拒绝、终态/重试/Outbox 一起回滚、终态更新 0 行不推进 Run。

既有 HTTP 鉴权与协议、业务重试、超时与迟到结果、Cron、Outbox、节点检查和服务发现测试全部包含在 Scheduler 的 73 项回归中。没有将 H2 通过替代真实 MySQL 的并发验证。

修复后证据：

- `.tools/run-state-mysql/20261027-150157-324/tests.log`：MySQL 15 项通过，日志确认实际 MySQL 数据源与隔离 schema。
- `.tools/run-state-mysql-after.log`：实例版本、隔离级别和最终结果。
- `.tools/run-state-scheduler-verify.log`：Scheduler 73 项通过与 JAR 打包结果。

## 可重复验收与安全边界

`tools/test-run-state-mysql.ps1` 要求 MySQL 的 `mysqld.exe`、`mysql.exe`、`mysqladmin.exe` 在 PATH 中。每次在 `.tools/run-state-mysql/<时间戳>/data` 初始化全新测试实例，只监听回环地址，默认端口 13387，可通过 `-Port` 修改。脚本创建随机 `taskflow_it_run_state_*` schema，结束后恢复环境变量并停止自己启动的实例，保留日志与临时数据供检查。

数据库 URL、认证参数入口、会话时区与 REPEATABLE READ 统一声明在 `application-test-run-state-mysql.yml`；脚本通过测试专用环境变量选择端口与随机 schema，不读取业务本机 YAML 的连接信息。测试初始化器在创建数据源和执行 Flyway 前，只允许隔离 H2 或具有专用 schema 前缀的本机 MySQL URL。默认 Maven 验证不需要 MySQL。

MQ 测试执行真实 Scheduler 事务和 Outbox 读取，模拟 Broker 已确认的数据库边界；未连接真实 RabbitMQ，不宣称完成 Broker 集群验收。本轮没有重跑浏览器或整套跨服务进程验收，没有替换运行中的业务服务。

本次预防后续状态回退，不会自动修复此前已经不一致的历史记录。历史数据需另行对账后修复，不能盲目重跑业务任务。后续已完成 [扫描公平性修复](dispatch-fairness-verification.md)，本文件保留该阶段的原测试计数；Worker 结果保存失败仍在 [审查报告](code-review-2026-10-27.md) 中保留为后续工作。

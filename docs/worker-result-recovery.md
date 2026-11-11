# Worker 执行结果保存恢复

我将结果保存与 Handler 执行分离。前台保存重试耗尽后，仍在运行的 Worker 可以补存原结果，并在数据库恢复后释放 Inbox 和本地执行容量。恢复使用 Worker 现有 Inbox、Attempt 事件表和结果上报协议。

## 保存结果的有限重试

Handler 结束后创建不可变 `ExecutionCompletion`，包含 Attempt、Run、终态、结果码、消息、业务重试标记和原始结束时间。`ExecutionResultServiceImpl` 实现 `IExecutionResultService`，负责保存这份结果；该 Service 不依赖 Handler，也不执行远程调用。

保存失败只重试本地 SQL，不重新执行 Handler、不创建 Run / Attempt、不增加 Scheduler 业务尝试次数。每次重试使用相同的结束时间和结果。SQL 仅允许 RUNNING 转终态，并检查影响行数。若 SQL 已提交但连接确认丢失，再次保存更新 0 行时查询原记录；相同 Run、终态、结果码、消息及业务重试标记才确认成功，不重置上报计数、下次上报时间或上报确认。记录缺失或终态冲突记录 `RESULT_SAVE_CONFLICT`，停止重试，不覆盖已有终态。

重试在原 Handler 执行线程中完成，等待期间没有 SQL 事务，原执行槽位继续占用。内存结果数量受 `parallelism` 限制，数据库中的 ACCEPTED / RUNNING 仍受 `max-pending` 限制。数据库故障期间处理吞吐会下降，不创建额外无界队列或重试线程。Handler 的超时计时器在其结束后取消，保存重试不会重新计为 Handler 执行超时。

| Worker YAML 配置 | 环境变量 | 默认值 | 校验 |
| --- | --- | --- | --- |
| `taskflow.execution.result-persistence.max-attempts` | `TASKFLOW_RESULT_SAVE_MAX_ATTEMPTS` | 5 | 1–10，包含第一次保存 |
| `taskflow.execution.result-persistence.retry-delay` | `TASKFLOW_RESULT_SAVE_RETRY_DELAY` | 500ms | 1ms–5s |
| `taskflow.execution.result-persistence.recovery-interval` | `TASKFLOW_RESULT_RECOVERY_INTERVAL` | 1s | 100ms–60s |

配置统一放 `application.yml`，测试使用独立 `application-test.yml`（3 次、10ms），无业务数据库连接参数硬编码。配置更新默认重启生效。总等待由 `(次数 - 1) × 间隔` 加每次数据库访问耗时组成；这不是整个方法的硬截止时间，数据库连接及 Socket 超时继续由数据源 YAML 控制。

## 保存恢复后继续上报

SQL 将真实终态和待上报字段一起保存成功后，由原有 `reportPending()` 扫描并在事务外调用 Scheduler。上报使用原 Attempt、Run 和 `leaseVersion`。保存重试与 HTTP 结果重报各有独立次数和间隔；前者不消耗后者的预算。

Scheduler 继续验证当前 Attempt、状态、租约版本与有效期。恢复发生在有效租约内时，真实结果可以被接受；租约已失效时，不承诺恢复出的成功结果一定能改写 Scheduler。原有 409 确认语义不变：停止发送被权威状态拒绝的结果，不表示业务成功。Worker 保存的本地终态不是全局权威状态。

## 耗尽、冲突与中断的诊断

| 日志 code | 含义 |
| --- | --- |
| `RESULT_SAVE_RETRY` | 结果保存访问数据库失败，将等待后重试 |
| `RESULT_SAVE_RECOVERED` | 第 2 次及以后保存成功或确认原提交 |
| `RESULT_SAVE_EXHAUSTED` | 数据库访问持续失败，前台重试耗尽，原结果移交恢复扫描 |
| `RESULT_SAVE_CONFLICT` | 记录缺失、状态不允许或终态不匹配，停止保存 |
| `RESULT_SAVE_INTERRUPTED` | 重试等待被中断，保留中断标记并移交原结果 |

失败诊断为 ERROR，携带 `attemptId/runId/intendedState/resultCode/retryable/finishedAt/attempts/type`；不输出 Handler 参数、消息正文、SQL 异常正文或凭据。Micrometer 注册：

- `taskflow.worker.result.persistence.active`：当前正在保存结果的数量。
- `taskflow.worker.result.persistence`：按固定 `outcome=saved/recovered/exhausted/conflict/interrupted` 分类的计数；`recovered` 是 `saved` 的子集。没有以任务 ID 作为指标标签，也没有额外开放公开监控端点。

## 同一进程内恢复

我从本地领取前开始跟踪 Attempt。Handler 已退出且前台保存耗尽或中断后，原 `ExecutionCompletion` 连同原执行槽位移交恢复扫描。当前正在执行、保存或等待恢复的记录合计至多 `parallelism`。数据库不可用期间，已有 `ACCEPTED` 和 `RUNNING` 继续占用 Inbox 容量。恢复扫描使用独立的单线程定时任务，每轮对每份结果最多尝试一次，数据库异常时把该结果移到待恢复队尾并结束本轮。后台扫描沿用 `taskflow.execution.scanning-enabled` 开关。

恢复原结果时，在同一事务更新本地终态、待上报时间和事件，写入 `RESULT_RECOVERED`。`HANDLER_FINISHED` 的时间沿用原 Handler 收尾时间。确认原结果已提交时保留上报进度及原事件，直接释放槽位。状态冲突保留原终态并记录诊断。Inbox 容量在终态提交后释放。原结果体因完整性约束无法保存时，恢复扫描尝试以受控的未知结果收尾。

扫描也读取当前执行会话中缺少进程内跟踪的 `RUNNING` 记录，例如领取 UPDATE 已提交但 JDBC 确认丢失。收尾时再次检查 Run、Attempt、状态和执行会话，在同一事务写入 `FAILED / RESULT_OUTCOME_UNKNOWN`、`retryable=false` 和 `RESULT_RECOVERY_UNKNOWN` 事件。记录可在运行详情查看。排查时用 Run、Attempt 和执行身份核对原 Handler 入口、Scheduler 权威状态与外部业务流水。进程崩溃时内存中的结果可能丢失，启动恢复沿用 `WORKER_RESTARTED`。

恢复沿用原执行身份上报。Scheduler 已使租约失效时，迟到结果按 `REPORT_STALE` 收尾。Scheduler 可按 Job 快照的重试策略另建 Attempt，业务仍需用业务幂等键和流水核对副作用。恢复过程中的日志使用 `RESULT_RECOVERED` 和 `RESULT_OUTCOME_UNKNOWN`，指标见 [指标字典](observability-metrics.md)。

## 故障回归与执行准备失败

任务已从 ACCEPTED 领取为 RUNNING 后，如果提交线程池被拒绝，使用同一个结果保存 Service 保存 `FAILED / EXECUTOR_REJECTED`；解析执行内容等准备失败则保存 `EXECUTION_PREPARATION_FAILED`。两者都表示 Handler 未启动，`retryable=false`，不直接重做业务。收尾结束后释放原槽位；领取本身抛出数据库异常时释放槽位并交给下一轮处理原记录，不伪造执行结果。

故障测试覆盖：

1. 首次终态保存失败后恢复，原成功结果保存并上报，重复派发也只执行一次 Handler。
2. Handler 真实失败时保存重试不更改失败结果及业务可重试标记，上报沿用原租约且在事务外进行。
3. SQL 实际提交后注入确认丢失，确认原终态，不重置上报状态和结束时间。
4. 前台保存失败严格到次数上限，后台扫描在同一 Worker 进程内恢复原结果。模拟启动恢复保留副作用未知语义。
5. 已有冲突终态和缺失记录均不误判保存成功，不覆盖终态。
6. 两个槽位正在保存时，后续任务继续等待，超过待处理上限返回 429；恢复后原任务继续完成。
7. 领取失败释放槽位并可继续原 ACCEPTED 工作；领取后线程池拒绝也能在一次保存故障后收尾，Handler 调用次数为零。
8. 保存等待可被停机中断，不消耗剩余次数；单次保存策略和无效配置边界。

修复前先运行前两项缺陷复现（首次保存失败、线程池拒绝）：2 项均未通过，分别表现为收尾等待超时和未处理的拒绝异常，证据 `.tools/worker-result-before.log`。修复后执行结果另见下方验证记录。

独立进程验收增加可选 `tools/test-execution.ps1 -InjectWorkerResultSaveFailure`。测试辅助类 `tools/local/FailFirstResultSave.java` 仅在隔离的 `.tools/execution-e2e/` 文件库中通过测试专用 Flyway 路径加载；生产 JAR 和正式迁移不包含它。触发器让首次 RUNNING → 终态 UPDATE 真实抛错并回滚，之后允许正常保存；验收同时检查 Worker 的重试/恢复日志与 Scheduler 中原结果、单个 Attempt。该方式使用真实 SQL 失败及真实 HTTP 上报，仍不是 MySQL 网络故障测试。

## 2026-10-27 短时故障验证记录

执行命令：

```powershell
.\mvnw.cmd -pl taskflow-worker test
.\mvnw.cmd verify
.\tools\test-execution.ps1 -SkipBuild -SkipBrowser -InjectWorkerResultSaveFailure
```

| 验证 | 结果 |
| --- | --- |
| 修复前缺陷复现 | 2 项均失败，保留原缺陷证据 |
| Worker 模块测试 | 37 项通过，0 失败、错误或跳过；比原有 26 项新增 11 项 |
| 最终根目录 verify | 六个应用 BUILD SUCCESS；196 项中 191 项通过、5 项条件跳过，0 失败、0 错误 |
| SQL 故障注入器检查 | 首次 UPDATE 回滚、第二次成功；拒绝指定隔离目录以外的数据库 |
| 独立进程 HTTP 故障验收 | 43 项通过，包含真实终态 SQL 回滚、保存恢复、Scheduler 接受原结果、Cron、重启恢复及节点重连 |
| PowerShell 验收脚本语法 | 通过 |

根验证中的 5 项跳过仍为 Admin 真实 Redis 连接 1 项、真实 Redis 缓存 3 项、Admin 专用 MySQL 迁移 1 项，不计入通过项。结束时间一致性测试比较第一次实际落库值，避免将数据库 TIMESTAMP(3) 的精度转换误当作截断。

证据：`.tools/worker-result-before.log`、`.tools/worker-result-step1.log`、`.tools/worker-result-step2-3.log`、`.tools/worker-result-root-verify.log`、`.tools/worker-result-trigger-check/probe.log`；独立进程日志为 `.tools/worker-result-process-verification.log`。

独立进程证据目录：`.tools/execution-e2e/20261027-193544-504/`，`report.json` 含 43 项 PASS。首个任务日志显示同一 Attempt 第一次保存失败、第二次恢复；Scheduler 保存 `SUCCEEDED / 执行成功` 且只有一个 Attempt。验收结束后已检查该目录对应的临时 Java 进程全部退出。

模块故障测试使用隔离 H2 和真实 Service / SQL，通过 Mapper Spy 注入访问异常；独立进程故障验收使用真实 H2 触发器和 HTTP。两者都不代表真实 MySQL 断网或 RabbitMQ 集群故障验收。浏览器测试未执行；业务实例未升级或重启。

## 2026-11-11 P01 验收记录

环境为 Windows、Java 21、H2 2.4 和隔离回环 MySQL 8.0.34（REPEATABLE READ）。进程验收通过真实 HTTP 连接 Scheduler 和 Worker，使用独立的订单接口测试替身记录每次业务请求。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `.\mvnw.cmd -B -o -l .tools/p01-root-verify.log verify` | 根构建通过，393 项通过，50 项按条件跳过。 | `.tools/p01-root-verify.log` |
| `.\tools\test-run-state-mysql.ps1 -IncludeWorkerResultRecovery` | Worker 2 项、Scheduler 15 项通过。真实 SQL 触发器验证长期保存失败、429 背压、恢复后容量释放，以及未知终态和事件写入一起回滚。 | `.tools/run-state-mysql/20261111-100649-605/worker-result-tests.log`、`tests.log` |
| `.\tools\test-execution.ps1 -SkipBuild -SkipBrowser -InjectWorkerResultSaveExhaustion` | 60 项检查通过，包含同一 Worker 进程内有效租约与失效租约下的原结果补存。 | `.tools/execution-e2e/20261111-103437-055/report.json`、`worker-result-recovery.json`、`worker-result-ledger.jsonl` |
| 在 `taskflow-web` 执行 `npm.cmd test` 和 `npm.cmd run build` | 29 项通过，前端构建成功。 | 终端输出 |

两条 P01 进程用例均在 Worker PID `63960` 内完成。H2 触发器持续拒绝终态 UPDATE，直到执行线程耗尽原保存预算。解除故障后，Worker 对两个原 Attempt 补存 `SUCCEEDED`。有效租约对应 `REPORT_ACKNOWLEDGED`，Scheduler 保留原成功结果。失效租约对应 `REPORT_STALE`，Scheduler 保留 `FAILED / LEASE_EXPIRED` 和原 `TIMED_OUT` Attempt。测试流水中每个 Run 只有一条业务调用记录，Worker 时间线各有一次 `HANDLER_STARTED`。MySQL 验收使用 SQL 触发器，真实数据库网络中断、RabbitMQ 故障切换和浏览器渲染未纳入本轮验证。

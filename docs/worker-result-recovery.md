# Worker 执行结果保存恢复

日期：2026-10-27。业务变更仅限 Worker `execution` 能力，不新增中间件、生产数据库迁移或 HTTP 协议字段。

## 第一步：保存结果的有限重试

Handler 结束后创建不可变 `ExecutionCompletion`，包含 Attempt、Run、终态、结果码、消息、业务重试标记和原始结束时间。`ExecutionResultServiceImpl` 实现 `IExecutionResultService`，负责保存这份结果；该 Service 不依赖 Handler，也不执行远程调用。

保存失败只重试本地 SQL，不重新执行 Handler、不创建 Run / Attempt、不增加 Scheduler 业务尝试次数。每次重试使用相同的结束时间和结果。SQL 仅允许 RUNNING 转终态，并检查影响行数。若 SQL 已提交但连接确认丢失，再次保存更新 0 行时查询原记录；相同 Run、终态、结果码、消息及业务重试标记才确认成功，不重置上报计数、下次上报时间或上报确认。记录缺失或终态冲突记录 `RESULT_SAVE_CONFLICT`，停止重试，不覆盖已有终态。

重试在原 Handler 执行线程中完成，等待期间没有 SQL 事务，原执行槽位继续占用。内存结果数量受 `parallelism` 限制，数据库中的 ACCEPTED / RUNNING 仍受 `max-pending` 限制。数据库故障期间处理吞吐会下降，不创建额外无界队列或重试线程。Handler 的超时计时器在其结束后取消，保存重试不会重新计为 Handler 执行超时。

| Worker YAML 配置 | 环境变量 | 默认值 | 校验 |
| --- | --- | --- | --- |
| `taskflow.execution.result-persistence.max-attempts` | `TASKFLOW_RESULT_SAVE_MAX_ATTEMPTS` | 5 | 1–10，包含第一次保存 |
| `taskflow.execution.result-persistence.retry-delay` | `TASKFLOW_RESULT_SAVE_RETRY_DELAY` | 500ms | 1ms–5s |

配置统一放 `application.yml`，测试使用独立 `application-test.yml`（3 次、10ms），无业务数据库连接参数硬编码。配置更新默认重启生效。总等待由 `(次数 - 1) × 间隔` 加每次数据库访问耗时组成；这不是整个方法的硬截止时间，数据库连接及 Socket 超时继续由数据源 YAML 控制。

## 第二步：保存恢复后继续上报

SQL 将真实终态和待上报字段一起保存成功后，由原有 `reportPending()` 扫描并在事务外调用 Scheduler。上报使用原 Attempt、Run 和 `leaseVersion`。保存重试与 HTTP 结果重报各有独立次数和间隔；前者不消耗后者的预算。

Scheduler 继续验证当前 Attempt、状态、租约版本与有效期。恢复发生在有效租约内时，真实结果可以被接受；租约已失效时，不承诺恢复出的成功结果一定能改写 Scheduler。原有 409 确认语义不变：停止发送被权威状态拒绝的结果，不表示业务成功。Worker 保存的本地终态不是全局权威状态。

## 第三步：耗尽、冲突与中断的诊断

| 日志 code | 含义 |
| --- | --- |
| `RESULT_SAVE_RETRY` | 本次数据库访问失败，将等待后重试 |
| `RESULT_SAVE_RECOVERED` | 第 2 次及以后保存成功或确认原提交 |
| `RESULT_SAVE_EXHAUSTED` | 数据库访问持续失败，次数耗尽，停止自动保存 |
| `RESULT_SAVE_CONFLICT` | 记录缺失、状态不允许或终态不匹配，停止保存 |
| `RESULT_SAVE_INTERRUPTED` | 重试等待被中断，停止保存并保留中断标记 |

失败诊断为 ERROR，携带 `attemptId/runId/intendedState/resultCode/retryable/finishedAt/attempts/type`；不输出 Handler 参数、消息正文、SQL 异常正文或凭据。Micrometer 注册：

- `taskflow.worker.result.persistence.active`：当前正在保存结果的数量。
- `taskflow.worker.result.persistence`：按固定 `outcome=saved/recovered/exhausted/conflict/interrupted` 分类的计数；`recovered` 是 `saved` 的子集。没有以任务 ID 作为指标标签，也没有额外开放公开监控端点。

耗尽或中断后，不再保留完整内存结果、不无限重试、不把本地记录重新置为 ACCEPTED。此时数据库可能仍为 RUNNING，负载统计与待执行限额也仍计入该记录；不能在数据库不可用时假装已持久化一个新状态。日志是此次已知结果的排查线索，指标是进程内累计值，均不替代结果数据库。数据库恢复到重试窗口以外时，不会自动再次保存已经丢弃的结果。

排查时使用日志中的 Run / Attempt 查询 Scheduler 权威历史，再核对实际业务副作用。不能仅凭 RUNNING、保存失败或 Scheduler 超时就断言业务未执行。结果尚未成功保存时若进程崩溃或重启，继续由启动恢复将遗留 RUNNING 记录标记为 `FAILED / WORKER_RESTARTED`、`retryable=false`，表示外部副作用未知。该处理不是还原原始结果，不会绕过 Scheduler 的租约验证，也不承诺任意业务恰好执行一次。

## 第四步：故障回归与执行准备失败

任务已从 ACCEPTED 领取为 RUNNING 后，如果提交线程池被拒绝，使用同一个结果保存 Service 保存 `FAILED / EXECUTOR_REJECTED`；解析执行内容等准备失败则保存 `EXECUTION_PREPARATION_FAILED`。两者都表示 Handler 未启动，`retryable=false`，不直接重做业务。收尾结束后释放原槽位；领取本身抛出数据库异常时释放槽位并交给下一轮处理原记录，不伪造执行结果。

本轮新增故障测试包括：

1. 首次终态保存失败后恢复，原成功结果保存并上报，重复派发也只执行一次 Handler。
2. Handler 真实失败时保存重试不更改失败结果及业务可重试标记，上报沿用原租约且在事务外进行。
3. SQL 实际提交后注入确认丢失，确认原终态，不重置上报状态和结束时间。
4. 持续保存失败严格到次数上限，日志可按 Run / Attempt 定位，后续扫描不重新执行；模拟启动恢复保留副作用未知语义。
5. 已有冲突终态和缺失记录均不误判保存成功，不覆盖终态。
6. 两个槽位正在保存时，后续任务继续等待，超过待处理上限返回 429；恢复后原任务继续完成。
7. 领取失败释放槽位并可继续原 ACCEPTED 工作；领取后线程池拒绝也能在一次保存故障后收尾，Handler 调用次数为零。
8. 保存等待可被停机中断，不消耗剩余次数；单次保存策略和无效配置边界。

修复前先运行前两项缺陷复现（首次保存失败、线程池拒绝）：2 项均未通过，分别表现为收尾等待超时和未处理的拒绝异常，证据 `.tools/worker-result-before.log`。修复后执行结果另见下方验证记录。

独立进程验收增加可选 `tools/test-execution.ps1 -InjectWorkerResultSaveFailure`。测试辅助类 `tools/local/FailFirstResultSave.java` 仅在本轮 `.tools/execution-e2e/` 文件库中通过测试专用 Flyway 路径加载；生产 JAR 和正式迁移不包含它。触发器让首次 RUNNING → 终态 UPDATE 真实抛错并回滚，之后允许正常保存；验收同时检查 Worker 的重试/恢复日志与 Scheduler 中原结果、单个 Attempt。该方式使用真实 SQL 失败及真实 HTTP 上报，仍不是 MySQL 网络故障测试。

## 验证记录

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

模块故障测试使用隔离 H2 和真实 Service / SQL，通过 Mapper Spy 注入访问异常；独立进程故障验收使用真实 H2 触发器和 HTTP。两者都不代表真实 MySQL 断网或 RabbitMQ 集群故障验收。本轮未修改前端，不重跑浏览器测试；没有升级或重启当前业务实例。

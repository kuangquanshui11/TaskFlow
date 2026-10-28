# 手动执行闭环验证与本机运行

日期：2026-10-26。已完成分阶段计划第三步，采用三个独立进程、版本化 HTTP、各自数据归属及 controller/service/impl/mapper 目录。接口详见 [API](api.md) 与 [内部协议](execution-protocol.md)。

## 使用方法

本机入口为 http://localhost:8090。创建任务后，打开“执行记录”或任务详情，点击“立即执行”。页面展示 Run 状态、每次 Attempt、结果信息和原配置快照，并在运行未结束时自动刷新；关闭详情后停止刷新。

提交未确认时显示“重试本次提交”“查验提交状态”，并在浏览器会话中保留原 requestKey。重新打开详情也沿用原标识，避免响应丢失造成重复 Run。“放弃重试”明确提示不取消可能已接收的运行；人工再次执行使用新的 Run。

内置 Handler：

| 标识 | 行为 |
| --- | --- |
| demoSuccess | 返回成功 |
| demoFail | 明确失败，按快照 maxAttempts 有限重试 |
| demoFailOnce | 首次 Attempt 失败，后续 Attempt 成功 |
| demoSlow | payload.durationMillis 控制 0–60000ms 的模拟耗时，缺省 1000ms |

启停管理配置；已停用配置也允许手动调试。Cron 配置可以手动执行，当前没有 Cron 到期自动触发。最大尝试次数包含首次执行。

## 服务实现

| 服务 | 新业务位置 | 数据与职责 |
| --- | --- | --- |
| Admin | run/controller、service、service/impl、client、dto、config、exception | 校验数据库 Job 版本，构造完整快照，调用 Scheduler；不执行 Handler，不读取其他服务的表 |
| Scheduler | execution/controller、service、service/impl、mapper、model、dto、config、client、exception | Run/Attempt、唯一请求标识、配置快照、待派发扫描、有限重试、租约和权威终态 |
| Worker | execution/controller、service、service/impl、mapper、model、dto、config、client、exception | 持久化去重 Inbox、执行容量、超时中断、终态与待上报结果 |

所有 Service 通过 IxxxService 接口注入，对应实现位于 service/impl。Admin 没有本地运行表，因此 run 模块不创建空 Mapper。Scheduler / Worker 的 JDBC Mapper 仅访问各自 schema，使用各自 V1 Flyway 迁移，没有跨服务 Maven 应用依赖、SQL、外键或本地事务。

三份独立密钥分别保护控制查询、派发和结果上报；空密钥拒绝内部请求。Admin 展示缓存不参与执行版本校验。SQL 提交和远程调用不描述为原子操作：Scheduler 提交后扫描派发，Worker 提交 Inbox 后确认，提交终态后扫描重报。

## 本机启动与更新

当前已创建 taskflow_scheduler、taskflow_worker 两个独立 schema 和独立账号，实际密码和密钥保存在被忽略的本机 YAML。Admin 业务表没有写入验收任务。三个服务均已从 .tools/runtime 中的 JAR 副本启动，避免锁住 target 下的构建产物。

首次准备与启动：

```powershell
.\mvnw.cmd verify
.\tools\prepare-local-execution.ps1
.\tools\services.ps1 -Action start
```

日常状态与更新：

```powershell
.\tools\services.ps1 -Action status
.\tools\services.ps1 -Action restart -Service admin
# 可选服务为 admin、scheduler、worker、all；只停止脚本记录且身份匹配的进程。
.\tools\services.ps1 -Action stop
```

服务端口：Admin 本机 18080、Scheduler 8081、Worker 8082，管理台 8090。也可按 README 分别用 Maven 启动，三个模块的插件都将工作目录设为项目根目录，以正确读取本机 YAML。IDE 同样使用根目录工作路径。

prepare-local-execution.ps1 读取已有 Admin YAML 的本机管理连接，仅准备独立空 schema、独立授权和随机密钥，不读取其他服务业务表。已有本机配置不会被覆盖；目标 schema 非空而尚无对应本机配置时拒绝自动接管。服务实际连接使用独立账号，运行时不需要共享 Admin 账号。

配置文件：

- config/taskflow-scheduler-local.yml：Scheduler 数据源。
- config/taskflow-worker-local.yml：Worker 数据源。
- config/taskflow-internal-local.yml：三份内部密钥。
- 各服务 application.yml：连接超时、扫描、有限重试、资源上限等参数；实际值可由本机 YAML 或环境变量覆盖。

上述本机文件均在 .gitignore 中；前端不包含内部地址、数据库凭据或服务密钥。

## 自动化与构建结果

实际根目录命令：

```powershell
.\mvnw.cmd -o -B '-Daether.syncContext.named.factory=rwlock-local' -l .tools/verification/execution-root-verify.log verify
```

结果：BUILD SUCCESS，65 项通过、4 项原有远程 Redis 检查按默认条件跳过，无失败、无错误。三个服务均生成独立可执行 JAR。离线与锁参数仅适配当前工具沙箱，不影响服务配置。

| 验证组 | 数量 | 结果与范围 |
| --- | --- | --- |
| 原有任务管理、缓存、启动与边界测试 | 44 | 全部通过 |
| Admin RunApiTests | 4 | 权威快照、事务外 HTTP、原请求在 Job 修改/删除后仍可重试、版本冲突和服务不可用 |
| Scheduler RunExecutionTests | 9 | HTTP 认证、并发唯一请求、创建事务回滚、回调早到不回退终态、传输重试同 Attempt/同截止时间、租约与重复终态、有限业务重试与耗尽 |
| Worker WorkerExecutionTests | 8 | 持久化后接收、重复派发只执行一次、异步结果和有限重报、首次失败、超时、重启恢复、容量与处理器校验 |

新增执行测试在创建 DataSource / 执行 Flyway 之前通过上下文初始化器确认独立 H2，避免环境变量覆盖测试 YAML 后误访问真实数据库。Worker 的 RUNNING → WORKER_RESTARTED 恢复和 ACCEPTED 保留，通过独立 H2 的实际存储与恢复用例验证；不把该用例描述成多节点故障转移测试。

## 三进程与浏览器验收

运行：

```powershell
.\tools\test-execution.ps1 -SkipBuild
```

不提供 SkipBuild 时会先执行根目录 verify；可用 SkipBrowser 仅运行三进程 API 与重启验证。脚本为每次运行创建 .tools/execution-e2e/<时间戳>，三个服务使用独立 H2 文件数据库，端口 19080/19081/19082，测试 Nginx 使用 8091。启动前清除可能覆盖测试配置的环境项，并在任何可写请求之前从实际日志确认 H2 文件路径；测试结束停止自己启动的进程。

本次证据目录为 .tools/execution-e2e/20261026-184059-539，report.json 的 12 项真实 HTTP/重启检查和浏览器组检查均为 PASS：

1. 未认证内部 API 返回 401。
2. 相同 requestKey 返回同一个 Run。
3. 三个真实 HTTP 服务完成成功执行。
4. 失败结果持久化并可查询。
5. 业务重试保留 Run，新增 Attempt，首次失败后成功。
6. 执行超时后失败，不自动重做未知副作用。
7. Job 可在执行后独立修改。
8. Run 保留原版本与完整快照。
9. Job 修改后，原请求仍返回原 Run。
10. 旧租约不能覆盖终态。
11. 停止并重启 Scheduler 后，已完成历史仍在。
12. Worker 停止时接受的待派发工作，在 Scheduler/Worker 重启后继续使用原 Attempt 完成。

前端 npm test：9 项全部通过，包括未确认请求标识持久化、版本变化后原请求保留、成功后新建 Run，以及旧响应不能清除新请求。生产构建成功。

Chrome / Playwright：8 项全部通过（原有 6 项 + 新增 2 项），覆盖真实点击执行、状态跟踪、Attempt 历史、模拟响应丢失、刷新后重试不新增第二个 Run，以及 375px 下详情无横向溢出。没有页面 JavaScript 错误。截图为证据目录的 execution-desktop.png、execution-mobile.png；Playwright 报告位于 taskflow-web/playwright-report。

## 实际本机检查与限制

- Admin、Scheduler、Worker 健康检查均为 UP。Scheduler / Worker 的真实 MySQL 空库 V1 迁移成功；本机账号分别只授权自己的 schema。
- 已在 8090 激活新版前端，确认返回的新 JS 包包含执行功能；经 Nginx 查询运行历史返回 OK。只读检查时 Job 1 的运行数为 0，没有注入测试 Run。
- 真实可写三服务验收使用隔离 H2 文件库；没有把 H2 结果描述为真实 MySQL 写入、性能或故障基准。真实 MySQL 这轮验证了迁移、连接、授权元数据和只读查询。
- Cron 自动触发、配置同步、动态 Worker 注册、心跳、续租、跨节点接管、RabbitMQ 与登录权限尚待后续阶段。一个 Worker schema 对应一个执行进程。
- 租约过期、超时中断和重复请求去重，不保证任意外部副作用恰好一次。执行槽位直到 Handler 实际退出才释放；不响应中断的 Handler 可能继续占用槽位。
- 结果重报有限，网络长期不可用或进程在未知执行阶段中断时会产生失败/未知结果；后续通过对账与可靠消息扩展，不宣称数据库提交与网络回调原子完成。

下一步为 Cron 计划同步与到期扫描，继续保留 Job、Run、Attempt 的职责和版本语义。

## 2026-10-27：Worker 派发扫描与结果上报隔离

本节追加本轮验证，前面的三进程与浏览器记录保留为历史验收。

- `ExecutionConfiguration` 的 `scan()` 仅调用 `executePending()`；新增 `workerReportScheduler`，单线程、线程名前缀 `taskflow-report-`，由独立 `report()` 使用相同扫描间隔和初始延迟执行 fixedDelay 上报。两者均遵守 scanning-enabled，节点心跳仍独立。
- `ExecutionProperties` 末尾增加 `reportBudget`，校验非空且范围为 (0, 30s]；application.yml 默认 2s，可用 `TASKFLOW_REPORT_BUDGET` 覆盖。
- `reportPending()` 入口计算截止时间，每条记录在 claim 前检查，耗尽后只记一次 debug 并结束。保留 recovered 门控、先 claim 再发送、事务外 HTTP、有限重报和 at-least-once 语义。没有修改协议字段、表结构、Scheduler、Admin 或 Maven 依赖。

新增 6 项测试，命名与现有 Worker 测试一致；已有断言未改动：

| 测试类 / 方法 | 验证内容 |
| --- | --- |
| ExecutionConfigurationTests.scanOnlyDispatchesPendingExecutions | scan 仅派发，从不上报 |
| ExecutionConfigurationTests.reportOnlyReportsPendingResults | report 仅上报 |
| ExecutionConfigurationTests.disabledScanningSkipsDispatchAndReporting | 开关关闭后两者均不执行 |
| ExecutionConfigurationTests.reportingUsesDedicatedSingleThreadSchedulerAndFixedDelay | 指定独立单线程调度器、线程名、fixedDelay 和相同 initialDelay，无 fixedRate |
| ExecutionConfigurationTests.reportBudgetMustBePositiveAndAtMostThirtySeconds | null、零、负数和超过 30s 均拒绝；1ms 和 30s 可用 |
| WorkerExecutionTests.slowReportingDefersRemainingResultsWithoutClaimingAfterBudget | 独立 H2 准备三条到期、未上报的成功/失败终态；预算 1ms，mock 每次上报阻塞 200ms；最多一次发送，其余仍未上报，领取次数和 next_report_at 不变；发送前已领取且不在事务中 |

预算测试允许首次查询已耗尽 1ms 时零发送，符合“每条记录在 claim 前检查”的约束。原有 `WorkerExecutionTests.resultReportingHasAFiniteAttemptBudget` 保留原断言，验证失败上报最多三次。

根目录 PowerShell 执行命令：

```powershell
.\mvnw.cmd -pl taskflow-worker test
.\mvnw.cmd verify
```

Worker 测试：BUILD SUCCESS，26 项，零失败、零错误、零跳过，包括上述新增测试与有限重报回归。

根目录 verify：BUILD SUCCESS，六个服务模块均成功构建；共 161 项，零失败、零错误，5 项原有条件测试跳过（实际通过 156 项）。Worker 的 26 项全部通过，新增预算测试与原有有限重报测试均再次通过。两条命令退出码均为 0。

首次受限执行未能打开用户 Maven 缓存中的锁文件；取得构建所需的缓存访问权限后，使用原命令重跑，没有更改 POM、测试 profile 或断言。构建日志保存在 `.tools/verification/report-budget/worker-test.log` 和 `root-verify.log`。

轮次预算只在记录之间生效：若单条 HTTP 最多 T，预算 B 的单轮网络等待估算上界为 B + T。按题设单条 3s，默认 2s + 3s = 5s；按当前 Feign 的连接/读取两个超时阶段保守相加，约为 2s + (2s + 3s) = 7s。另加数据库、DNS、本地处理和调度开销，读取超时也不是整个 HTTP 调用的硬截止时间，不能宣称方法严格在该时间内返回。fixedDelay 的 500ms 属于轮次间隔，在途请求不会被预算中断，派发扫描不再等待上报。

README 的“当前进度”与本次改动没有冲突，保持原文。当前工作目录没有 `.git` 元数据，无法执行 git diff；通过修改前后的文件校验值核对源码与文档范围，忽略 target 构建产物及 .tools 验证日志。

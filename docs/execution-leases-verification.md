# 执行租约验收

日期：2026-10-28。范围为固定 Worker 的有界续租、执行者校验、Handler 上下文与既有状态/恢复回归；协议和限制见 [执行租约](execution-leases.md)。

## 执行与结果

```powershell
.\mvnw.cmd -B verify
.\mvnw.cmd -B -pl taskflow-worker verify
.\tools\test-run-state-mysql.ps1 -IncludeExecutionLease
.\tools\test-execution.ps1 -SkipBuild -SkipBrowser -IncludeExecutionLease
```

| 验证 | 结果 |
| --- | --- |
| 根目录 verify，六个独立服务 | 通过；228 项，0 失败、0 错误、6 跳过；包含 Scheduler 94 项、Worker 57 项及可执行 JAR |
| 最终 Worker verify | 通过；补充 HTTP 408/429/永久拒绝分类后共 65 项，0 失败、0 错误、1 跳过；重新打包 JAR |
| MySQL 8.0.34 / REPEATABLE READ | 26 项通过，0 失败、0 错误、0 跳过；Run 状态并发 15 项、执行租约 11 项 |
| 真实 Gateway/Admin/Scheduler/Worker 进程，隔离 H2，HTTP | 48 项通过；新增执行租约 7 项，既有运行/配置同步/Cron/节点/重启回归保留 |
| 两个 PowerShell 验收脚本语法 | 通过 |

根验证的跳过项为显式依赖外部 Redis/MySQL/RabbitMQ 的可选验收；最终 Worker 跳过真实 RabbitMQ 背压测试。本轮没有把跳过项计作通过，没有连接业务数据库或替换已有运行实例。

## 故障和并发覆盖

Scheduler 使用真实事务、Service 和条件 SQL，验证：

- 固定 Worker 与执行会话首次绑定；后续续租保留版本、原派发截止时间及 Attempt 数量。
- 错误 Worker、旧/不同会话、错误 Run、错误版本拒绝；原短租约已过期但扫描尚未执行时，也不能续租或提交结果。
- 每次续租受硬截止约束；已有执行者可以在结果宽限内续租和上报，首次领取已无执行预算则拒绝。
- 超时扫描对旧候选锁内复查；SQL 拒绝错误所有者、过期或终态记录。
- 结果事务持有 Run 锁时，并发续租先读到旧 Attempt、随后等待；结果提交后续租必须根据当前终态拒绝，不受 RR 快照影响。
- Worker 领取成功后，原派发请求即使返回 400/503 且已到派发次数上限，也不能覆盖已开始执行的状态。

Worker 使用真实执行线程、独立续租线程、独立超时计时器和隔离数据库；续租响应通过测试替身控制，验证：

- 长任务跨越初始短租约，多次续租仍只调用一次 Handler。
- 短暂网络故障在已确认窗口内恢复；明确拒绝立即失去资格；续租请求阻塞不阻塞失租检查。
- 首次领取结果不确定或执行预算已耗尽时，不调用 Handler，不请求自动业务重试。
- 健康续租不能延长执行超时；忽略中断的 Handler 在实际退出前持续占用槽位。
- 结果保存期间仍续租，执行超时中断不会打断保存；既有结果保存恢复/耗尽测试保留。
- 时间计算扣除请求已耗时间，不依赖两机墙钟相同；取消后的迟到确认不能复活资格。
- 同一 Run 不同 Attempt 产生相同的业务动作幂等键，Attempt 标识和 fencing 版本各自保留用途。
- HTTP 408、429、5xx 仍属不确定失败；400、401、404、409 为明确拒绝；客户端单次调用不重放 POST。

## 真实进程续租闭环

`test-execution.ps1 -IncludeExecutionLease` 将隔离 Scheduler 租约设为 10s、Worker 续租间隔设为 500ms；运行 `demoSlow` 12s、执行预算 20s，其他服务连接、鉴权和扫描从隔离 YAML 装配。

新增 7 项检查实际经 HTTP 完成：绑定真实 Worker、短租约确实延长且硬截止/版本不变、拒绝不同 Worker、拒绝不同执行会话、续租后的原消息仍匹配 Inbox fingerprint、跨初始短租约成功且没有第二次 Attempt、终态不能续租复活。该测试同时通过 Admin/Gateway 查询真实 Scheduler 权威历史。

本轮未重跑浏览器、Nginx、真实 RabbitMQ 或 cloud 注册发现链路；没有模拟真实 JVM GC 暂停或接入实际扣款/发货下游。阻塞、线程忽略中断和迟到确认使用可控故障测试覆盖相应边界，不能据此声称任意外部副作用恰好一次。

## 本机证据

- `.tools/execution-lease-root-verify.log`：根 verify 与服务级测试计数。
- `.tools/execution-lease-worker-verify.log`：最终 Worker 65 项和 JAR 打包。
- `.tools/execution-lease-mysql.log`、`.tools/run-state-mysql/20261028-150537-464/tests.log`：隔离 MySQL 版本、隔离级别与 26 项结果。
- `.tools/execution-lease-e2e.log`、`.tools/execution-e2e/20261028-150723-939/report.json`：48 项真实进程检查及各服务日志。

`.tools` 保持忽略，不纳入提交；验收脚本结束后停止自己启动的服务/MySQL 实例，保留隔离数据和日志供本机核对。

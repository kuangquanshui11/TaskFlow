# 2026-10-27 代码审查

本轮发现 3 个已复现的问题。现有自动化测试通过，不能据此认为并发和故障恢复已经完整覆盖。本轮没有修改业务实现；复现程序和日志保存在忽略目录 `.tools/review-20261027/`。

后续状态：问题 1 已按用户确认采用 MySQL 行锁修复，见 [状态并发修复验证](run-state-concurrency-verification.md)；问题 2 已通过持久化延后检查修复，见 [扫描公平性验证](dispatch-fairness-verification.md)；问题 3 已补充结果保存的有限重试、恢复上报、耗尽诊断及线程池拒绝收尾，见 [结果保存恢复](worker-result-recovery.md)。以下保留原审查原因与复现记录。

## 范围和限制

2026-10-28 后续进展：固定 Worker 的有界执行续租、执行者校验及 Handler 执行上下文已实现，见 [执行租约](execution-leases.md)；跨节点接管和任意外部副作用恰好一次仍不在交付范围。本文后续建议保留为原审查时的记录。

当前目录没有 `.git`，不能可靠计算“今天提交的 diff”。按 2026-10-27 的文件修改时间检查可靠派发、Worker 注册/心跳、Feign/发现/网关、执行与结果上报、启动配置及相关测试，并沿调用链读取关联代码。因此，以下问题属于当前版本，不能断言全部由今天的修改引入。

根构建、前端单元测试和前端生产构建为本轮重新执行。浏览器端到端测试和真实 RabbitMQ 集群本轮未重跑，不沿用旧报告宣称本轮通过。MySQL 复现使用全新隔离的数据目录、13387 端口及测试 schema，不连接业务 MySQL；实例已停止。

## 1. P1：并发时已成功的 Run 会退回 RUNNING

位置：`taskflow-scheduler/src/main/java/com/taskflow/scheduler/execution/service/impl/RunServiceImpl.java` 的 `locked()`（236–239 行）和 HTTP 派发确认（143–146 行）；`RunDataMapper.accepted()` / `runState()`。

`locked()` 先普通读取 Attempt，再锁住 Run，之后再次普通读取 Attempt。MySQL 默认 REPEATABLE READ 下，第一次普通读取已建立快照；等待 Run 锁之后的第二次普通读取仍可能得到旧 Attempt。Run 锁不能让另一个表的普通快照读取自动刷新。这与 [MySQL 8.0 隔离级别说明](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html) 一致。

确定性复现：

1. Worker 接收任务，结果回调事务先持有 Run 锁。
2. 派发确认事务普通读取 Attempt，读到 DISPATCHING，随后等待 Run 锁。
3. 回调把 Run、Attempt 都更新为 SUCCEEDED 并提交。
4. 派发确认取得 Run 锁，但重读 Attempt 仍得到旧的 DISPATCHING。
5. `accepted()` 因数据库中的 Attempt 已终结而更新 0 行，调用方未检查影响行数，继续无条件把 Run 更新为 RUNNING。

真实 MySQL 8.0.34、REPEATABLE-READ，调用实际 RunServiceImpl 的结果：

```text
REVIEW CONCURRENT_CALLBACK: run=RUNNING, attempt=SUCCEEDED, expected both SUCCEEDED
```

影响：运行历史状态不一致，且过期扫描只查询非终态 Attempt，这种 Run 不会通过普通租约过期扫描自动收尾。单实例 Scheduler 同样可能触发，因为 HTTP 结果回调与派发线程并发。

建议：保持 Run → Attempt 的统一锁顺序，取得 Run 锁后对 Attempt 使用当前锁定读取；状态 SQL 同时保护状态/当前 Attempt/租约，并返回影响行数。只有 Attempt 的状态转换成功才允许推进 Run，Run 更新也必须拒绝终态回退。补充真实 MySQL 的并发回调、重复回调和派发确认竞争测试，不能仅依赖 H2。

证据：`.tools/review-20261027/mysql-probe.log`；复现代码 `ReviewProbe.concurrentCallback()`。本轮隔离空 MySQL 也成功应用 Scheduler V1–V6，不能替代已有数据库的增量升级验证。

## 2. P1：100 条不可派发任务会挡住后面的正常任务

位置：`RunServiceImpl.claim()` 205 行，以及 `RunDataMapper.due()` / `mqDue()`。

扫描按 next_dispatch_at 排序、每次最多读取 100 条。节点不具备 Handler 等条件时，`claim()` 直接返回，不更新等待时间。这些记录保持 READY、没有 expiresAt，下轮仍占据最前面的 100 个名额。

复现：创建 100 条目标节点不支持 Handler 的 Run，再创建 1 条支持的 Run。节点在线，`canDispatch("demoSuccess")` 为真。连续执行 5 轮真实扫描：

```text
REVIEW STARVATION http: valid run state=READY, dispatchCount=0, scans=5, blocked predecessors=100
REVIEW STARVATION rabbitmq: valid run state=READY, dispatchCount=0, scans=5, blocked predecessors=100
```

两种传输分支均在隔离 H2、实际 Service/Mapper 上复现；节点资格和 HTTP 客户端使用测试替身，没有实际连接 Broker。业务触发示例是 Worker 升级移除某个 Handler，旧快照任务不断累积。

建议：无法派发时持久化等待原因和下一次检查时间，或采用有游标的公平扫描；等待不消耗派发次数，不提前领取执行租约。仅增大 LIMIT 不能根治。覆盖超过一个扫描批次的混合 Handler、能力恢复、HTTP/MQ 分支。

证据：`.tools/review-20261027/probe-http.log`、`probe-rabbitmq.log`；复现代码 `ReviewProbe.starvation()`。

## 3. P2：结果落库暂时失败后，Worker 本地记录一直 RUNNING

位置：`taskflow-worker/src/main/java/com/taskflow/worker/execution/service/impl/ExecutionServiceImpl.java` 的终态持久化与异常处理（136–139 行）；`ExecutionMapper.pending()` / `reports()` / `recoverRunning()`。

Handler 返回之后，如果 `mapper.finish()` 因短暂数据库故障失败，当前逻辑只写日志并释放执行槽位。记录仍为 RUNNING；执行扫描只读取 ACCEPTED，上报扫描只读取 SUCCEEDED/FAILED，因此数据库恢复后普通扫描也无法处理它。启动恢复只能在服务重启时将其改为 WORKER_RESTARTED，不能恢复此前真实成功结果。

向实际 ExecutionServiceImpl 注入一次终态 SQL 的暂时性异常，此后数据访问恢复。Handler 只执行一次，再执行 5 轮执行/上报扫描：

```text
REVIEW FINISH_FAILURE: worker state=RUNNING, reportCount=0, handlerCalls=1
```

影响：成功结果丢失，Scheduler 最后只能按租约过期处理；Worker 本地负载和待执行容量被遗留记录占用。不能笼统说 Scheduler 也永久 RUNNING，两边恢复机制不同。

建议：把“Handler 已结束、终态待保存”与执行动作分开，对结果写入做有限重试，耗尽后有明确告警和可对账状态。不得为重试结果落库重新执行 Handler；进程崩溃或结果已丢失时保留副作用未知语义。还应覆盖执行领取成功后入线程池失败的收尾。

证据：`.tools/review-20261027/probe-worker.log`；复现代码 `ReviewProbe.workerFinishFailure()`。

## 本轮验证

| 项目 | 结果 |
| --- | --- |
| 根目录 `mvnw.cmd verify` | BUILD SUCCESS，六个应用独立打包 |
| Registry / Config Server / Gateway | 1 / 1 / 6 项通过 |
| Admin | 69 项中 64 项通过，5 项按外部环境条件跳过 |
| Scheduler | 58 项通过 |
| Worker | 26 项通过 |
| 后端合计 | 161 项，156 项通过，5 项跳过，0 失败/错误 |
| 前端 `npm test` | 9 项通过 |
| 前端 `npm run build` | 成功 |
| 额外复现 | MySQL 并发状态回退、HTTP/MQ 扫描饥饿、Worker 终态写入失败均复现 |

根验证日志：`.tools/review-20261027-verify.log`。额外复现是在确认当前缺陷仍存在，不属于修复后的回归测试通过。

## 后续按小步推进

1. **先修并发状态回退。** 本次只调整 Scheduler 状态读取和转换保护。验收：真实 MySQL 并发下 Run/Attempt 都保持正确终态，重复回调幂等，旧租约拒绝。
2. **再修扫描公平性。** 为不可派发任务持久化安排后续检查。验收：前面至少 100 条不可派发时，后面的正常任务仍能派发；等待不增加 Attempt 或派发次数。
3. **补 Worker 结果保存恢复。** 结果保存独立于 Handler 执行。验收：短暂 SQL 故障恢复后，真实终态可保存和上报，Handler 始终只执行一次。
4. **补真实基础设施验收。** 先验证隔离 MySQL 从旧 V4/V5 增量升级，再验证真实 RabbitMQ 三节点的确认、消费确认、重复投递和单节点故障。每次一种故障，保留实际证据；环境未就绪时不宣称完成。
5. **单独实现执行续租。** 固定 Worker 内先完成 Attempt 心跳、状态/leaseVersion/身份检查和有效期延长；Worker 节点心跳继续只表示节点存活。
6. **最后做失联恢复。** 先明确副作用未知时哪些任务允许重试，再实现旧租约隔离与故障对账；跨节点接管另开验收范围。

第 1、2、3 步已按用户确认实施，第 3 步的次数上限、进程崩溃与副作用未知边界见 [结果保存恢复](worker-result-recovery.md)。后续先补真实基础设施验收，再分别实施执行续租和失联恢复。固定 Worker 路由、没有跨节点接管、没有完整配置对账与真实 MQ 集群验收属于已记录的阶段边界，本轮没有把它们重复包装成新发现的缺陷。

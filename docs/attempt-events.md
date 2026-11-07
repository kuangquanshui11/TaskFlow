# Attempt 执行事件

## 归属与关联

我在 Scheduler V10 增加 `scheduler_attempt_event`，在 Worker V3 增加 `worker_attempt_event`。两个服务各自写入本地数据库，用 `runId`、`attemptId`、`workerId` 对照。Scheduler 的 Worker 标识来自 Attempt 已绑定目标或不可变派发消息。Worker 使用 Inbox 持久化的执行者标识，因此重启补报仍能关联原执行者。历史记录缺少该标识时保留空值。

两张表按 `(run_id, attempt_id, id)` 建索引。诊断时用完整 Run 和 Attempt ID 过滤，并按本地事件 ID 排序。事件时间为服务观察时刻，跨服务时钟及提交顺序可能不同。事件只保存标识、状态、结果码、时间及相关序号，参数快照、Handler 输出、异常正文和密钥留在各自原有边界内。

## Scheduler

| 事件 | 记录条件 |
| --- | --- |
| `HTTP_DISPATCH_CLAIMED`、`MQ_DISPATCH_PREPARED` | 目标已绑定，派发状态及硬截止随本地事务提交 |
| `HTTP_DISPATCH_ACCEPTED`、`HTTP_DISPATCH_REJECTED`、`HTTP_DISPATCH_UNCONFIRMED` | 收到 Worker 的接收响应、确定拒绝或未获确认；派发序号标识该次请求 |
| `MQ_PUBLISH_CLAIMED`、`MQ_PUBLISHED`、`MQ_PUBLISH_UNCONFIRMED`、`MQ_PUBLISH_EXHAUSTED` | Outbox 领取、有效领取下确认或未确认、未知结果耗尽；发布序号独立于 Attempt 派发序号 |
| `LEASE_ACQUIRED`、`LEASE_RENEWED` | 首次成功领取及之后实际变更的租约截止时间；拒绝请求或相同截止时间不追加 |
| `ATTEMPT_FINISHED` | 权威 Attempt 进入终态，租约失效体现为 `TIMED_OUT / LEASE_EXPIRED` |
| `RUN_FINISHED` | Run 到达最终状态，关联最后一个 Attempt；自动重试期间先保存当前 Attempt 的终态 |

我把事件和对应的本地状态变更放在同一事务。HTTP 和 Broker 操作仍在事务外。HTTP 接收确认可以晚于结果上报，MQ 确认表示 Broker 已接管消息。Relay 发布后、本地确认前退出会留下领取事件，恢复后仍可重发同一 Outbox 消息。重复终态上报不再写终态事件。

## Worker

| 事件 | 记录条件 |
| --- | --- |
| `HANDLER_STARTED` | 领取租约且通过执行资格检查后，进入调用 Handler 的边界前写入 |
| `HANDLER_FINISHED` | 已进入 Handler 的执行结果成功保存 |
| `EXECUTION_FINISHED`、`EXECUTION_RECOVERED` | 调用前失败，或重启收尾本地 RUNNING 工作 |
| `REPORT_CLAIMED` | 本次上报计数和下次上报时间已提交，随后在事务外发起请求 |
| `REPORT_ACKNOWLEDGED`、`REPORT_STALE` | Scheduler 确认结果，或返回 409 表示该结果已无法覆盖权威状态；两种情况都停止重报 |
| `REPORT_UNCONFIRMED`、`REPORT_EXHAUSTED` | 未获确认，按既有预算安排后续上报或结束重试 |

我先持久化 Handler 入口事件，再检查执行资格并调用 Handler。入口写入未获确认时，本地结果使用 `HANDLER_START_UNRECORDED`，跳过 Handler。入口与 Handler 调用之间可能发生退出或资格失效，因此入口事件本身不证明业务副作用已发生。结果事件与 Inbox 终态和待上报字段一起提交，结果保存重试沿用同一份结果。上报结果由本地事务记录，远程响应后退出可能只留下 `REPORT_CLAIMED`，它表示上报结论未知。

## 升级与范围

启动时由两个服务分别执行 Flyway 新迁移；历史数据继续使用原状态，事件从升级后的写入开始采集。事件随数据库保留，当前没有自动归档。交付范围是记录与持久化，后续再接有界查询代理和控制台时间线。公网 API 和服务间请求字段沿用原契约。

## 验证

2026-11-07（Asia/Shanghai），Windows、Java 21.0.12.1、Maven 3.10.0。使用只包含基线和本次改动的独立快照，离线执行构建与隔离 H2 测试。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `mvn.cmd -B -o -pl taskflow-scheduler,taskflow-worker verify` | 两个模块均构建成功。Scheduler 最终代码 134 项通过，0 跳过 | `.tools/attempt-events-snapshot-verify.log` |
| `mvn.cmd -B -o -pl taskflow-worker verify` | Worker 补充事务创建失败回归后重新构建成功，86 项通过，1 项真实 Broker 条件测试跳过 | `.tools/attempt-events-worker-final-verify.log` |

集成测试覆盖 HTTP 响应晚于终态、MQ 发布确认丢失和重发、派发准备回滚、租约领取及续租、同一 Run 的多次 Attempt、迟到结果拒绝、Worker 入口记录失败、结果保存回滚和提交确认丢失，以及上报重试、耗尽和重启补报。未执行真实 MySQL 迁移或真实 Broker 故障演练。

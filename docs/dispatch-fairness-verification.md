# 派发扫描公平性修复验证

日期：2026-10-27。修复范围为 Scheduler 的 HTTP 派发准备、MQ 派发准备及 MQ Relay 发布候选。继续使用 Java 21、当前 Spring Boot、JdbcTemplate 和 MySQL，修复范围不涉及新增中间件、数据库迁移或服务间协议字段。

## 原因与修复

原扫描按时间取最前面 100 条。节点不在线、Handler 不支持或路由不匹配时直接跳过，不修改检查时间，同一批记录会在每轮占据候选名额，导致后面的正常任务无法派发。

扫描策略：

- 准备阶段在已锁定 Run / Attempt 的事务中，将不可派发 Attempt 的 `next_dispatch_at` 推迟。
- 发布阶段在已锁定 Outbox 的事务中，将不可发布事件的 `next_publish_at` 推迟。READY 和过期 SENDING 均遵守该时间；候选被其他扫描器延后后，领取事务再次检查时间并跳过。
- 候选按下次检查时间、记录 ID 稳定排序，保留每批 100 条上限，不通过扩大批次掩盖阻塞。
- 更新包含状态、租约或领取版本及原到期条件；旧候选不能覆盖已安排的下一次检查、新领取或取消。SQL 与事务仍归属 Scheduler。
- SENDING 延后时不更改 state、claimVersion 或 claimUntil；未过期 SENDING 不允许延后。旧 Publisher 仍无法用过期领取租约确认，下一次实际领取才增加版本和次数。
- 等待不新增 Run / Attempt，不增加派发或发布次数，不新增或延长执行租约。HTTP 重派发沿用已有租约；MQ 保留原消息、事件标识和 expiresAt。执行租约到期仍可独立扫描终结并取消 Outbox，不受未来检查时间阻挡。

复用现有 `run_attempt`、`dispatch_outbox` 字段与索引。具体实现位于 `RunServiceImpl.claim()`、`RunDataMapper.deferDispatch()`、`DispatchRelayServiceImpl.claim()`、`DispatchOutboxMapper.deferPublication()`。

## 配置与恢复行为

`taskflow-scheduler/src/main/resources/application.yml` 新增：

```yaml
taskflow:
  dispatch:
    node-recheck-delay: "${TASKFLOW_NODE_RECHECK_DELAY:2s}"
```

有效范围为 100ms–5m，默认 2s；不合法配置拒绝启动。该间隔与 HTTP 传输重试间隔、MQ 发布失败退避分别管理，只用于等待派发资格。

节点能力恢复后，任务在原记录保存的下一次检查时间到达后重新判断，还需加上扫描间隔和积压处理时间；心跳不立即绕过该时间。服务重启继续使用数据库中的检查时间。该机制不承诺任意积压负载下固定的派发延迟。

公开 API 的路径、参数和响应结构不变，已有 `attempts[].nextDispatchAt` 可表示下一次派发或资格检查时间。未派发记录继续为 READY，未准备的 Outbox 继续为 WAITING。详细业务边界见 [节点协议](worker-nodes.md)、[执行协议](execution-protocol.md)、[API](api.md) 和 [可靠派发](reliable-dispatch.md)。

## 修复前后验证

先加入最初 5 项回归测试，再修改实现。旧代码全部失败，失败原因与扫描饥饿缺陷一致：

| 场景 | 原实现 |
| --- | --- |
| HTTP：250 条不支持 Handler 的任务后跟正常任务 | 连续扫描仍未派发正常任务 |
| MQ 准备：同上 | 正常任务未准备，派发次数仍为 0 |
| Relay：100 条不可发布 READY 后跟正常事件 | 正常事件未发布 |
| Relay：100 条不可发布的过期 SENDING 后跟正常事件 | 正常事件未发布 |
| 已准备任务等待发布 | 未持久化延后的检查时间 |

修复后新增测试共 9 项，通过独立 YAML 将测试检查间隔设为 5m，避免测试依赖实际 sleep；通过精确推进测试记录的到期时间验证恢复，不修改业务配置。

1. HTTP / MQ 两条准备路径：250 条阻塞任务后，正常任务在后续批次被处理；阻塞记录保持原 Attempt、leaseVersion、次数和空租约。重新构造 Service 后仍等待保存的检查时间，到期恢复原 Attempt。
2. READY / 过期 SENDING 两条发布路径：100 条阻塞事件让出候选名额；恢复后发布原消息。等待期间计数、claimVersion、claimUntil、expiresAt 不变，旧 Publisher 仍被拒绝。
3. 已准备任务在等待中执行租约过期，仍转为 TIMED_OUT，Run 失败，Outbox 取消。
4. HTTP 已有派发失败后等待节点恢复，保留原截止时间和已花费次数，恢复时继续同一 Attempt。
5. Attempt 延后 SQL 执行后注入故障，事务回滚原检查时间，不领取执行租约。
6. Outbox 延后 SQL 执行后注入故障，事务回滚原检查时间及领取状态。
7. SQL 拒绝重复旧候选延后、有效新领取、较新领取版本和取消记录。

旧节点测试中的恢复步骤也显式推进资格检查时间，不再把“节点恢复”和“立即到期”混为一件事。

## 验证结果

```powershell
# 新建隔离 MySQL 实例，同时回归此前状态并发修复
.\tools\test-run-state-mysql.ps1 -IncludeDispatchFairness
# 完整 Scheduler 回归和可执行 JAR 打包
.\mvnw.cmd -pl taskflow-scheduler verify
```

| 验证 | 结果 |
| --- | --- |
| 修复前公平性测试 | 5 项失败，已保留失败证据 |
| 修复后真实 MySQL 8.0.34 / REPEATABLE READ | 公平性 9 项、状态并发 15 项，共 24 项通过，无失败、错误或跳过 |
| Scheduler 完整 verify，独立 H2 | 82 项通过，无失败、错误或跳过 |
| Scheduler JAR | 重新生成成功 |
| MySQL 验收脚本语法 | 通过 |

证据：

- `.tools/dispatch-fairness-before.log`、`.tools/dispatch-fairness/before-tests.txt`：原实现失败记录。
- `.tools/run-state-mysql/20261027-152623-318/tests.log`：真实 MySQL 24 项通过，日志确认实际数据源、隔离 schema 及迁移。
- `.tools/dispatch-fairness-mysql.log`：MySQL 版本、隔离级别与测试最终结果。
- `.tools/dispatch-fairness-verify.log`：82 项通过和打包结果。

MySQL 脚本复用 [状态并发验收](run-state-concurrency-verification.md) 的全新本机测试实例、随机专用 schema 和启动前数据源保护；退出时停止自己的实例，保留证据，不连接或迁移业务库。

MQ 测试使用真实数据库事务与模拟 Publisher，不代表真实 RabbitMQ 集群故障验收；2026-10-27 未执行浏览器或整套跨服务进程测试，业务实例未重启。Worker 终态保存暂时失败后的恢复机制另见 [结果保存恢复](worker-result-recovery.md)。

# 派发进程故障窗口验证

## 验证范围与复现

验证日期：2026-10-30。环境为 Windows、Java 21、当前项目构建的 Scheduler / Worker 可执行 JAR、RabbitMQ 3.8.34 三节点集群。每轮创建随机 `taskflow_it_<GUID>` vhost、四个三副本 quorum 队列，以及 Scheduler、两个 Worker 各自独立的 H2 文件库和随机端口。Worker 身份、队列、Inbox 分别隔离；应用使用设置文件中的独立服务账号。

```powershell
.\mvnw.cmd -pl taskflow-scheduler,taskflow-worker test
.\tools\test-dispatch-faults.ps1 -ClusterSettingsPath .tools/mq-cluster-settings.json -AllowLegacyBroker -SkipBuild
```

不带 `-SkipBuild` 时先执行根目录 `mvnw.cmd verify`。需要本机 Java/Javac 21、Node.js、Maven 已下载的 H2 驱动及可访问的三个 Broker 节点和管理入口。设置文件格式沿用 [已有集群接入](reliable-dispatch.md#接入已有外部集群)。密码、实际地址和验收文件保留在忽略目录；测试 SQL、故障触发器和代理均不打入生产 JAR，不修改正式迁移或 HTTP 协议。

我把故障窗口固定在真实 SQL 更新前：`DispatchFaultProbe` 仅允许 `.tools/dispatch-faults/` 下的文件库，验收专用 Flyway 路径安装 H2 触发器。Relay 的屏障只有在真实 `RabbitDispatchPublisher.publish()` 完成后、`SENDING → PUBLISHED` 更新前才触发；随后对本轮创建的 Scheduler 进程执行强制退出，不走优雅停机。进程退出使未提交更新回滚，先前已提交的 SENDING 和已确认消息保留。

旧成功结果的屏障位于 Worker 已保存终态之后、第一次 `report_count` 递增之前。杀死进程后，该行的成功结果保留，上报领取回滚；旧 Inbox 在替代 Attempt 完成后重启，实际发送原结果。Scheduler Tomcat access log 只记录请求行和状态码，不记录密钥或正文；以新增的真实 HTTP 409、原 Inbox 的上报计数/确认和权威状态前后对比共同验证，未由脚本构造旧结果 POST。

## 故障矩阵及最终状态

这里的 Inbox 指 Worker 的 `worker_execution`，其 `reported=true` 表示收到权威端的接收或拒绝确认，不能解释为原业务成功。Scheduler 的 Run / Attempt 才是权威结果。

| 故障窗口 | 注入与恢复 | Run / Attempt | Outbox / Inbox 及断言 |
| --- | --- | --- | --- |
| 已发布但未标记时 Relay 退出 | Worker 已注册但消费者关闭；真实 confirm 后阻塞 SQL，强杀 Scheduler，过期后重启 Relay，再开启消费者 | 同一 Run、单个 Attempt 最终 SUCCEEDED，执行 leaseVersion 不变 | 退出后 SENDING、publishAttempts=1、Broker 一条消息、Inbox 空；重启后同一事件和完整 messageJson 再发，claimVersion=2、publishAttempts=2，Broker 两条消息；最终 PUBLISHED、单个 SUCCEEDED Inbox 且上报确认 |
| Worker 执行期间退出 | 确认已领取执行租约，强杀第一个 Worker；第二个独立 Worker 完成新 Attempt | 原 Attempt TIMED_OUT，第二个 SUCCEEDED；同一 Run 最终 SUCCEEDED | 强杀后旧 Inbox 仍 RUNNING；两个 Outbox 最终 PUBLISHED。旧 Worker 重启后保存 FAILED / WORKER_RESTARTED、retryable=false，真实上报获得 409，reported=true、reportCount=1；新 Inbox SUCCEEDED 且已确认；Run 与全部 Attempt 的数据库快照保持不变 |
| 旧 Worker 恢复后上报旧成功 | 原成功已落 Inbox，但首次上报前强杀；等待租约到期和替代 Attempt 成功，再启动旧 Inbox | 第一 Attempt 仍 TIMED_OUT，第二 Attempt SUCCEEDED，旧成功不能覆盖旧超时或新终态 | 两个 Outbox PUBLISHED；两个 Inbox 均保存各自本地 SUCCEEDED，旧行第一次真实上报收到 409 后确认；Run 和两个 Attempt 前后数据库字段完全相同 |
| 业务失败持续发生 | demoFail，配置快照 maxAttempts=2 | 同一个 Run 只生成两个 FAILED Attempt，最终 FAILED | 两个 Outbox，不额外创建 Run 或无限业务重试 |
| 连续两次 Worker 退出 | 依次在两个 Worker 领取执行资格后强杀；达到终态后再恢复两份旧 Inbox | 两个 Attempt 均 TIMED_OUT，Run FAILED；两个旧进程恢复后仍无第三个 Attempt | 两份原 Inbox 在强杀后均为 RUNNING；恢复后各自 WORKER_RESTARTED 并完成拒绝确认；两个 Outbox PUBLISHED，权威 Run / Attempt 快照不变 |
| 发布连接短暂中断 | 本轮专用 TCP 代理关闭并拒绝 Publisher 的真实 AMQP 连接；首次失败落库后，在发布预算内恢复 | 原 Run / Attempt 成功，不创建新的业务尝试 | 同一个事件在第 2 或第 3 次发布后 PUBLISHED，只有一个 Inbox；Relay 和 Worker 不重启 |
| 发布连接持续中断并恢复 | 中断持续到发布耗尽，再恢复连接、提交新 Run | 原 Run FAILED / MQ_PUBLISH_UNCONFIRMED，只有一个 Attempt；新 Run 成功 | 原 Outbox FAILED、publishAttempts=3、无 Inbox；连接恢复后原事件仍 FAILED 且次数保持 3，不自动重放；同一 Relay 可发布新事件 |

发布重试与业务重试使用不同预算：验收 YAML 为 `max-publish-attempts=3`、发布失败间隔 4 秒；业务快照为 `maxAttempts=2`，重试间隔 1 秒。执行租约 10 秒，续租 500ms；Relay 领取租约 8 秒，confirm 超时 1 秒。Relay 重发场景的派发有效期使用 120 秒任务超时，覆盖取证和两次进程启动的时间，不通过直接修改数据库时间推进恢复。

SQL 证据通过带 `IFEXISTS` 的只读 SELECT 获取，连接到相应服务的 H2 AUTO_SERVER；验收器读取三份数据库只用于外部核验，服务运行时不访问其他服务的数据。各快照之间不是跨数据库原子快照；对迟到结果采用替代 Attempt 已终结、旧上报已确认后的稳定字段比较。Broker 消息数通过管理接口等待统计刷新，不把瞬时旧统计误判为消息丢失。空死信队列只证明这些场景未留下意外死信，不代表可靠死信转移已验收。

## 证据和清理

2026-10-30 最终完整矩阵执行结果为 **16 项 PASS，0 项失败**，报告写入时间为 18:31:45（Asia/Shanghai）。本轮证据：`.tools/dispatch-faults/taskflow_it_43f10fbe4946421d8837900adfe6e803/`；命令日志：`.tools/dispatch-faults-final.log`。`scheduler-access.log` 保存四次旧结果真实上报的 409；最终四个队列均为三成员、三个在线副本，ready / unacknowledged 均为 0。成功后本轮 vhost 已删除。

证据目录为 `.tools/dispatch-faults/<随机 vhost>/`：

- `report.json`：日期、Broker 版本、全部断言及未验证边界；失败不会写成 PASS。
- `relay-after-kill.json`、`relay-broker-after-kill.json`、`relay-republished.json`、`relay-final.json`：Relay 窗口、消息数量、完整不可变事件及最终状态。
- `worker-before-kill.json`、`worker-after-kill.json`、`worker-replacement-complete.json`、`worker-restarted-final.json`：真实领取、执行中退出及旧 Inbox 恢复后的状态。
- `late-success-*.json`、`consecutive-crashes-*.json`：旧成功迟到和连续退出的前后状态。
- `publisher-transient-recovered.json`、`publisher-outage-exhausted.json`、`publisher-recovered.json`、`publisher-exhausted-after-recovery.json`：连接恢复与耗尽的区别。
- `scheduler-access.log` 和各进程日志：真实旧上报 409、连接中断/重连；`quorum-before.json`、`quorum-final.json`：本轮队列副本及死信计数。

finally 只停止验收器自己持有句柄的 Java / Node 进程。通过后只删除本轮随机测试 vhost，保留文件证据；失败时也关闭本轮进程，但保留 vhost、数据库及日志供排查。修复后使用新 vhost 重跑，失败资源应在核对证据后按该轮完整 vhost 名称清理，不清空业务队列、不删除数据卷。

模块回归在 2026-10-30 执行：Scheduler 104 项通过；Worker 66 项中 65 项通过、1 项条件跳过，0 失败、0 错误。跳过项为 `DispatchBackpressureBrokerTests`，本次 Maven 命令未设置它所需的 Broker 环境变量，不计入通过项；历史背压真实 Broker 结果仍见 [背压验证](dispatch-backpressure-verification.md)。本次未修改服务业务行为，未重跑浏览器、Admin/Gateway 登录链路或真实 MySQL 网络故障。

## 后续 Broker 节点故障恢复演练

我把连接中断与 Broker 节点故障分开验收。本次代理只切断测试 Publisher 到第一个 Broker 的连接，Worker 仍直连集群全部地址；没有停止 Broker 节点、制造 quorum 丢多数派或网络分区。[2026-10-28 节点停机记录](mq-verification.md) 是历史验证，不能替代同一批在途工作及同一应用进程的节点故障恢复。

下一轮按以下顺序在有维护窗口的测试集群执行：

1. 确认三个节点及四个测试 quorum 队列均为三成员、三副本在线，无分区和磁盘/内存告警；保存版本、leader、连接所在节点和管理快照。沿用随机 vhost 与独立数据，不改业务资源。
2. Scheduler / Worker 配置原始全部 AMQP 地址，不走单入口代理。保持应用进程、数据库和同一组测试消息不变，在已存在 SENDING 或未完成 Attempt 时停止当前连接节点；保存实际连接断开、leader 切换和 3→2 在线副本证据。
3. 等待剩余多数派处理原在途工作，核对原事件/Attempt 的幂等收敛、消息未因连接重建丢失、发布次数不超过预算。另在已验证的耗尽场景确认原事件不因节点恢复自动重放。
4. 恢复故障节点，等待所有测试队列恢复三个在线副本并无告警，再对原 Run、Attempt、Outbox、Inbox 和旧结果拒绝情况取证。失败时先恢复节点及健康，再保留隔离证据；不能用全新应用实例重跑成功代替原在途工作的恢复。
5. 多数派丢失、宿主机故障和网络分区放在独立测试集群的另一轮演练，不在共享集群中追加执行。

可靠死信转移单独安排：现有 RabbitMQ 3.8.34 使用显式旧版兼容策略，不能通过本轮空 DLQ 或 reject 成功声称已具备至少一次死信转移。升级或准备支持该策略的独立 Broker 环境后，再验证死信目标不可用期间的保留、恢复后的最终转移、重复去重、容量/告警及副本故障恢复；升级前先验证消息/定义备份、版本兼容与回退条件，不能以 definitions 导出代替消息备份。版本边界与既有依据见 [可靠派发](reliable-dispatch.md)。

# MySQL 与 RabbitMQ 派发故障验证

## 复现与故障窗口

我使用真实 MySQL、现有 RabbitMQ 三节点集群和三个独立服务进程验证故障恢复。每轮初始化只监听回环地址的临时 MySQL 实例，为 Scheduler、Worker、订单服务分别建立随机 schema；MQ 使用随机 `taskflow_it_<GUID>` vhost 和三副本 quorum 队列。测试不读取业务数据库连接，不清空业务队列。

前置条件：Java/Javac 21、Node.js、本机 `mysqld` / `mysql` / `mysqladmin`、项目构建依赖，以及三个可访问的 AMQP 地址和分别直连各节点的管理入口。设置文件沿用 [已有集群接入](reliable-dispatch.md#接入已有外部集群)，必须有独立 Scheduler / Worker 账号。

```powershell
.\mvnw.cmd -B -o -pl taskflow-scheduler,taskflow-worker,examples/taskflow-order-service verify
.\tools\test-dispatch-mysql-faults.ps1 -ClusterSettingsPath .tools/mq-cluster-settings.json -AllowLegacyBroker -SkipBuild -ConfirmNodeFailure
```

现代 Broker 可省略 `-AllowLegacyBroker`。不带 `-SkipBuild` 时脚本先执行三个模块的 `verify`。不带 `-ConfirmNodeFailure` 时第三个场景自动切断 Publisher 连接，报告标记为 `PUBLISHER_CONNECTION_LOSS`；该模式不能作为 Broker 进程退出的证据。

节点故障模式开始前需要三个节点全部在线。收到 `STOP NODE NOW: <node>` 后，只停止指定节点，保持其余两个运行；收到 `RESTORE NODE NOW` 后，恢复同一节点。两个检查点各等待最多 180 秒。测试失败时若提示 `RESTORE REQUIRED`，先恢复该节点并核对三个 quorum 副本，再重跑。需要在允许单节点停机的集群维护窗口执行，节点停机也会影响该节点上的其他连接。

| 窗口 | 可观测的屏障 | 恢复断言 |
| --- | --- | --- |
| MySQL 提交前强杀 Scheduler | 测试 DataSource 包装器在实际 `Connection.commit()` 前暂停，事务已写入 Run、Attempt、Outbox | 强杀回滚三张表，Worker Inbox 和 MQ 均无消息；同一请求键重试后只有一个 Run，订单关闭并只释放一次库存 |
| MySQL 提交后、AMQP 发布前强杀 Scheduler | 测试 Publisher 包装器在真实 `publish()` 前暂停，并要求当前无 SQL 事务；外部连接已能读到 SENDING Outbox | 租约到期后重发原事件，只有一个 Attempt，messageJson 与执行 leaseVersion 不变，Relay claimVersion / publishAttempts 增至 2 |
| Publisher Confirm 尚未返回时节点退出 | AMQP 帧代理截留真实 Broker 的 `Basic.Ack`，消息已存入 quorum，Publisher 的 confirm future 尚未完成 | 原 Scheduler 进程记录确认失败，经存活节点再次发布相同事件；开启消费者前队列中有两个副本，最终只有一条成功且已上报的 Inbox、一次 Handler 进入、一次库存释放 |

我把第三个窗口固定为 Broker 已接收消息、确认尚未送达应用的歧义窗口。代理不伪造 ACK，也不把超时当作消息未入队。它按 AMQP 帧边界截留 ACK 及其后的帧，原节点退出后丢弃该连接上未送达的确认；新连接依次尝试剩余地址。这验证至少一次派发下的去重，不代表任意外部副作用恰好执行一次。

故障屏障和只读取证器位于 `tools/local/MysqlDispatchFaultProbe.java`，仅由验收启动器加载，不打包到服务 JAR，也不增加正式 API、表或 Flyway 迁移。测试仍调用真实 Publisher、RabbitTemplate、MySQL 提交和订单 Handler。MySQL 数据源与连接池设置统一位于 `tools/acceptance/application-dispatch-mysql.yml`，采用 UTC 会话和 `REPEATABLE READ`。常规模式使用 1 秒确认超时和 8 秒 Relay 租约；人工节点操作期间使用 240 秒确认超时和 300 秒 Relay 租约，执行租约为 300 秒，不直接修改数据库时间推进恢复。

## 证据与边界

每轮 `.tools/dispatch-mysql/<vhost>/` 保存：

- `report.json`、`environment.json`：完成标记、故障类型、逐条断言时间、MySQL/Broker 版本、隔离级别、端口和 JAR SHA-256。
- `before-commit-*.json`：未提交事务、强杀回滚、请求键恢复后的数据库与业务状态。
- `after-commit-*.json`：强杀后的 Run / Attempt / Outbox / Inbox、业务状态、空队列及恢复后的原事件。
- `confirm*.json`：真实 ACK 的通道与 deliveryTag、截留时间、节点停机拓扑、重发前后消息数、最终状态和执行时间线。
- `*-migrations.json`、进程日志和 MySQL 数据目录：迁移版本、连接中断及恢复证据。

服务各自访问本服务数据库；验收器在服务外分别执行只读 SELECT，关联 Run、Attempt、Outbox、Inbox、订单、库存和流水。跨库快照不是原子快照，终态与上报确认后再核对稳定字段。通过后只删除本轮 vhost；finally 关闭本轮持有的应用和 MySQL 进程，保留数据库文件及证据。失败时保留本轮 vhost，便于排查，不自动删除失败资源。

本专项不覆盖多数派丢失、跨主机网络分区、宿主机退出、历史异常状态修复、付款与关单竞争、监控/追踪系统或浏览器回归。旧版 RabbitMQ 的空 DLQ 不能证明至少一次死信转移。

## 2026-11-18 实测

环境为 Windows、Java 21.0.12.1、MySQL 8.0.34 / InnoDB / `REPEATABLE-READ`、现有 RabbitMQ 3.8.34 三节点集群，代码基线为 `e5cf3ab`。Scheduler / Worker / 订单服务分别从空 schema 迁移到 V19 / V7 / V8，使用同一轮模块构建的 JAR，哈希保存在 `environment.json`。

上文带 `-ConfirmNodeFailure` 的命令于 09:46:09（Asia/Shanghai）完成，**18 项 PASS，0 项失败**。报告为 `.tools/dispatch-mysql/taskflow_it_d4b6cea56ef14ad8abbd2b887fdb53be/report.json`，命令日志为 `.tools/dispatch-mysql-node-faults-final.log`。

同一命令省略 `-ConfirmNodeFailure`，无人值守连接中断模式于 09:50:43（Asia/Shanghai）完成，**17 项 PASS，0 项失败**，三个场景均核对了相同的数据库和业务结果。报告标记为 `PUBLISHER_CONNECTION_LOSS`，位于 `.tools/dispatch-mysql/taskflow_it_227f4c0af7e048988d886633f6dc2dba/report.json`；日志为 `.tools/dispatch-mysql-connection-faults-final.log`。该轮成功清理了随机 vhost 和临时进程。

| 场景 | 本轮证据 |
| --- | --- |
| 提交前强杀 | 屏障处事务仍为 RUNNING，`trx_rows_modified=3`，外部连接读不到 Run、Attempt、Outbox；强杀后四类记录均为空，队列为空。同一请求键恢复后得到单个成功 Run，再次请求返回原 Run；订单 CLOSED，库存 available=1、reserved=0，RELEASED 流水一条 |
| 提交后发布前强杀 | 强杀后保留 SENDING Outbox、publishAttempts=1，Inbox 和 MQ 均为空，订单仍 PENDING；恢复后原事件 PUBLISHED、publishAttempts=2、claimVersion=2，messageJson 和执行 leaseVersion 不变，只有一个成功 Attempt 和一条已上报 Inbox，库存释放一条 |
| Confirm 未返回时 mq1 退出 | 真实 ACK 在 09:43:51 被截留，09:44:55 的节点离线快照记录 mq1=false、另外两个节点运行及两份在线副本。原 Scheduler PID 未变，事件经存活节点重新发布，开启消费者前队列有两份持久消息。最终原 Run / Attempt SUCCEEDED，Outbox PUBLISHED、发布两次，只有一条成功且已上报的 Inbox；时间线只有一次 HANDLER_STARTED，库存释放流水一条 |
| 恢复与清理 | mq1 恢复后两个 quorum 队列均有三个在线副本，ready / unacknowledged / DLQ 为零；本轮随机 vhost 已删除，应用与临时 MySQL 已停止，数据库文件和快照保留 |

模块回归使用上文 `verify` 命令：Scheduler 161 项中 159 项通过、2 项条件跳过；Worker 106 项中 103 项通过、3 项条件跳过；订单服务 86 项中 46 项通过、40 项条件跳过，全部 0 失败、0 错误。该 Maven 命令未启用的环境测试不计入通过项；本专项的真实 MySQL / MQ 结果来自上述独立进程验收。构建日志为 `.tools/dispatch-mysql-build.log`。

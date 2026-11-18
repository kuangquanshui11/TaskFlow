# 派发链路与集群故障验收

我用同一份 MySQL 与 RabbitMQ 验收启动器建立随机 vhost、三副本 quorum 队列和三个独立测试 schema。各场景使用真实 Scheduler、Worker、订单服务与派发协议。脚本通过只读查询记录 Run、Attempt、Outbox、Inbox，以及订单、库存和流水。场景接收的测试订单由脚本生成。

前置条件、独立账号和构建命令见 [MySQL 与 RabbitMQ 派发故障验证](dispatch-mysql-fault-verification.md)。以下命令适用于现有 RabbitMQ 3.8.34。

```powershell
.\tools\test-dispatch-mysql-faults.ps1 -ClusterSettingsPath .tools/mq-cluster-settings.json -AllowLegacyBroker -SkipBuild -Scenario PublisherShort
.\tools\test-dispatch-mysql-faults.ps1 -ClusterSettingsPath .tools/mq-cluster-settings.json -AllowLegacyBroker -SkipBuild -Scenario PublisherExhausted
.\tools\test-dispatch-mysql-faults.ps1 -ClusterSettingsPath .tools/mq-cluster-settings.json -AllowLegacyBroker -SkipBuild -Scenario WorkerLink
```

| 场景 | 注入与验收 |
| --- | --- |
| `PublisherShort` | 在第一条 Outbox 已提交且发布尚未调用时断开本轮 Publisher 的代理端口。记录未确认错误及剩余预算，再恢复连接。原 Run、Attempt、租约版本及消息正文保持一致，订单关闭、预占归零并产生一条释放流水。 |
| `PublisherExhausted` | 维持断连直到三次发布均耗尽。验证 `MQ_PUBLISH_UNCONFIRMED`、Outbox 失败且无 Worker Inbox 或库存释放。恢复后原 Run 不续发。核对零副作用后，由测试显式提交新请求，验收只释放一次。 |
| `WorkerLink` | 代理只隔离 Worker 发往 Scheduler 的 HTTP 流量。长 Handler 已开始后切断续租和心跳，验证 Worker 失租退出、节点离线，恢复后原进程上线并收到迟到上报拒绝。另一轮在订单事务已经提交、Worker 第一次发送结果时切断，验证权威 Run 保持租约超时，已提交的订单及释放流水不重复。 |

Publisher 代理中断时三个 Broker 节点仍然运行。Worker 链路测试在同一宿主机上控制两进程间的传输。脚本参数中的短租约、扫描间隔和发布预算仅用于验收。

## 多数派丢失与 2+1 分区

准备可停机的隔离三节点集群。独立 vhost 只隔离消息数据，停止节点或切断节点互联会影响该节点上的其他 vhost。对照设置文件中 AMQP 地址与直连管理地址的顺序，确认三项一一对应。用 `-DedicatedCluster` 显式确认环境。脚本还会检查故障前的 AMQP 连接以及每台 Broker 的独立身份。

在上面命令中改用对应 `-Scenario` 并加 `-DedicatedCluster`，每次单独执行一个场景。

1. `QuorumShort`。在脚本提示后停止指定两个节点，保留另一个节点。脚本要求被停节点的管理入口失联，幸存节点报告两个成员离线。待首轮发布未确认后按提示恢复，尽量在下一次 45 秒退避结束前恢复服务。最终检查原事件恢复、Attempt 身份不变和库存只释放一次。
2. `QuorumExhausted`。在脚本提示后停止指定两个节点，直到三次发布预算耗尽并出现恢复提示。恢复两个节点后核对旧 Run 的明确失败和无副作用，再验证一次显式新请求。
3. `BrokerPartition`。按提示在基础设施层双向隔离第一个节点与另两个节点的集群互联，维持多数侧两节点互通，以及测试机到三个管理入口和 AMQP 入口的访问。准备能在分区期间读取少数节点本地视图的集群配置。该模式将测试 Publisher 固定到少数节点，要求取得两侧不同的对等节点视图、多数侧队列领导者和两个在线副本。记录少数侧不能确认发布后移除分区，再检查三副本恢复和原事件重发。

测试暂停点只能等待有限时间。若出现 `RESTORE REQUIRED` 或脚本中断，先在隔离环境撤销本轮故障，确认原三个节点和队列副本均在线，再重跑。保留失败报告，分清故障未注入、未按预算恢复和实际业务断言失败。

## 物理宿主机退出

1. 使用三台物理宿主机，各放一个 Broker 节点。在拟退出的宿主机同时运行测试 Worker A，另一台宿主机运行 Worker B。两者的队列、Worker ID 和执行库各自独立。让 Scheduler、订单服务和依赖数据库运行在可存活的宿主机上，以便本项观察 Broker 与 Worker 的宿主机故障。测试用独立 vhost、schema 与订单。按 [Worker 水平扩展](worker-horizontal-scaling.md) 准备节点与路由。
2. 记录宿主机与 Broker、Worker 的映射、三个在线副本、两个 Worker 的能力和时间。触发一条允许重试的长 Run，查 **运行详情** 与 Attempt 时间线，确认 Worker A 已取得租约并开始 Handler。若首次派到 B，重新准备一条隔离测试 Run，直到取得发往 A 的证据。
3. 从独立控制端断开 A 的物理宿主机，并记录关机时间和独立控制端事件。核对 Broker A 与 Worker A 同时失联、多数侧仍有两个在线副本。提交一条新订单关单 Run，测从故障到下一条成功运行的时间。核对旧 Run 首次 Attempt 失租、重试仍沿用原 Run ID 和新 Attempt 经 B 完成。若任务配置未启用失租重试，按已定义的失败与核对策略验收。
4. 恢复相同宿主机和原数据卷，等三个副本在线、Worker A 重新注册。记录恢复时间。对每个测试订单核对订单终态、预占、可用库存和唯一释放流水，核对迟到上报未改写旧 Attempt。保存控制端关机/恢复事件及各服务只读证据。同一物理机上的三个虚拟机可以演练虚拟机退出。

## 证据

每轮报告位于 `.tools/dispatch-mysql/<vhost>/report.json`。同目录还保留 JAR 校验和、MySQL 版本和 schema 迁移、队列拓扑及各阶段快照。订单事务已提交时，平台超时记录与业务流水应能独立查询。RabbitMQ 3.8 的死信保障以 [旧版兼容说明](reliable-dispatch.md#隔离验收) 为准。

### 2026-11-18 验收

Windows、Java 21、隔离 MySQL 8.0.34、RabbitMQ 3.8.34 三节点，使用本页命令启动现有构建产物。

| 命令的 `-Scenario` | 完成时间（Asia/Shanghai） | 结果 | 证据目录（位于 `.tools/dispatch-mysql/`） |
| --- | --- | --- | --- |
| `WorkerLink` | 10:14:38 | 7 项通过 | `taskflow_it_b15f1993404d4ba8b777a06b0e42667a` |
| `PublisherShort` | 10:15:41 | 8 项通过 | `taskflow_it_81131dc3dd364377a08fdd26a0042fb9` |
| `PublisherExhausted` | 10:11:31 | 9 项通过 | `taskflow_it_296e01e5edcf4b6cbb9a9edd5572aa43` |
| `Baseline` | 10:18:43 | 17 项通过，确认在途场景采用发布连接中断 | `taskflow_it_630ca329ede947a3925deab4c0121bbd` |

PowerShell 与 Node 语法检查、拓扑判定夹具、无 `-DedicatedCluster` 的拒绝检查通过。实际多数派丢失、Broker 2+1 分区和物理宿主机关停仍需独立环境验收。

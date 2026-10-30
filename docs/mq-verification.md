# Cron 复测与可靠派发验证记录

2026-10-30 补充：真实 confirm 后强杀 Relay、执行中强杀 Worker、旧 Inbox 重启后真实上报旧失败/成功结果，以及发布连接中断/恢复的状态核验见 [派发进程故障窗口验证](dispatch-fault-verification.md)。该专项区分 Run、Attempt、Outbox、Inbox 的最终状态和两类重试预算；Broker 节点停机中的在途恢复与可靠死信转移仍单独安排。以下 2026-10-27 / 28 结果保留为历史记录。

2026-10-28 补充：Worker 满载改为保留原 delivery 等待容量，满载与过期投递不再转死信；Scheduler 按心跳负载推迟满载节点派发。两个服务完整回归 125 项通过，真实 Broker 背压专项 1 项及三服务 MQ 闭环 43 项通过。背压专项未注入 Broker 停机故障，未修改业务资源，完整结果与边界见 [容量背压验证](dispatch-backpressure-verification.md)。

初始验证日期：2026-10-27；远程集群、应用接入与节点故障补充验证日期：2026-10-28。远程 Docker 主机上的三个节点已组成集群，并分别在三个节点正常及一个节点停机时，通过真实三服务 MQ 执行验收。本记录区分真实 Broker、数据库集成测试和 Mock 边界故障注入，明确旧版死信与未测故障的限制。

## 2026-10-28 远程集群搭建与验证

集群环境使用三个 RabbitMQ 3.8.34 / Erlang 24.3.4.1 容器，节点名称分别为 `rabbit@mq1`、`rabbit@mq2`、`rabbit@mq3`。最初三个管理接口各自只返回一个节点。入群前确认没有队列、消息、客户端连接，三个节点的运行时 Erlang cookie 哈希一致，配置中已声明三个发现节点。

问题在于容器仅连接 Docker 默认 `bridge` 网络，不能解析其他容器的节点名称。实施步骤如下：

1. 在服务器 `/root/mq/cluster-backup-VI4tDg5b` 备份三个容器的部署元数据、挂载配置与 RabbitMQ definitions。备份目录仅 root 可访问，不将其中的凭据或账号哈希纳入仓库；definitions 不包含消息内容，入群前没有消息。
2. 创建用户自定义桥接网络 `taskflow-mq`，为三个现有容器添加同名网络别名，验证节点名称解析及 Erlang 节点通信。保留现有数据卷、配置挂载和端口映射。
3. 保留 `mq1` 为种子节点，依次对 `mq2`、`mq3` 执行 `stop_app`、`reset`、`join_cluster rabbit@mq1`、`start_app`。重置仅针对已确认无队列和消息的两个待加入节点。
4. 将集群名称设置为 `taskflow-mq`，为三个容器设置 `unless-stopped` 重启策略。

| 实测项目 | 结果 |
| --- | --- |
| 应用端口 5672、5673、5674 | 本机 Java AMQP 客户端分别认证成功并建立通道，使用默认 vhost `/` |
| 管理端口 15672、15673、15674 | 三个入口均返回相同的三个在线节点和集群名称 `taskflow-mq` |
| 集群健康 | 无网络分区、内存告警或磁盘告警 |
| 单容器重启 | 实际重启 `mq2`，随后 `await_online_nodes 3` 成功；重启后继续完成三节点认证与收发测试 |
| 临时 quorum 队列 | 创建随机命名的持久队列，`members` 和 `online` 均包含三个节点 |
| 跨节点收发 | 经 mq1 发布持久消息并收到 publisher confirm，经 mq3 拉取消费，消息内容一致并完成 ACK |
| 测试清理 | 删除测试临时队列；最终三个管理入口查询的队列数均为 0 |

上述为集群基础验证阶段，临时 quorum 队列验证完成后已删除。随后已创建 TaskFlow 资源并完成下节中的应用接入及带队列的单节点停机验证。仓库 Compose 模板使用 RabbitMQ 4.x，3.8 容器未使用该模板重建，按实际 Broker 版本显式选择兼容策略。

节点入群和 cookie、名称解析要求参考 [RabbitMQ 官方集群指南](https://www.rabbitmq.com/docs/3.13/clustering)。实际操作按远程已有的 3.8 版本执行停应用、重置、入群和启动流程。

## 2026-10-28 应用接入、真实 Broker 与单节点停机验收

接入结果：`connect-mq.ps1` 从本机已有的 init 定义导入 `taskflow` vhost、三个独立角色及资源权限、两个三副本 quorum 队列。Scheduler 仅发布派发交换机，Worker 仅消费固定队列；两个业务服务的真实验收日志分别出现 `taskflow_scheduler`、`taskflow_worker` 连接，应用未使用管理员账号。实际地址放入被忽略的 `config/taskflow-mq-cluster-local.yml`，账号密码仍保留在各服务自己的 MQ YAML，两个 application.yml 已补充共享连接文件的导入。

| 验证 | 实际结果 |
| --- | --- |
| 根目录 `mvnw.cmd verify` | BUILD SUCCESS；196 项中 191 项通过、5 项条件跳过，0 失败、0 错误；六个服务 JAR 构建成功 |
| 三节点正常、真实 MQ 三服务闭环 | 43 项通过；独立 Admin/Scheduler/Worker 与 Gateway 进程，各服务独立文件 H2 |
| 真实消息路径 | Scheduler 经远程 AMQP 发布、Worker 消费并完成 HTTP 结果上报；重复事件经真实 Broker 路由，原 Run/Attempt 与已完成 Inbox 保持一致 |
| Cron 与恢复 | 不经人工触发的 Cron 执行成功；业务重试、超时、Worker 注册/离线阻断、服务重启及待派发恢复均通过 |
| 单节点停机 | 实际 `docker stop --time 20 mq1`；两个队列原 leader 均为 mq1，停机后 leader 均变为 mq2，三个成员保留，在线副本变为 mq2/mq3 |
| 两节点期间重新运行完整闭环 | 43 项通过；应用仍配置全部三个地址，真实连接日志显示改连 5673，使用原测试 vhost 及原三成员队列 |
| 节点恢复 | `docker start mq1` 后两个队列恢复三个在线副本；三个管理入口均确认全部节点健康，无分区、内存或磁盘告警 |
| 清理与业务资源 | 随机测试 vhost 已删除；业务 `taskflow` vhost 与两个三副本队列保留 |
| 版本保护 | 对真实 3.8 Broker 未传 `AllowLegacyBroker` 时拒绝继续，避免静默降低死信保障 |

5 项跳过仍为 Redis 连接/缓存 4 项和 Admin 专用 MySQL 迁移 1 项，未计入通过项。外部集群接入使用独立配置入口、管理脚本和验收脚本，Java 业务行为、服务数据归属及公共 HTTP API 保持原协议。

复现命令（管理凭据和独立角色凭据已保存在被忽略的 `.tools/mq-cluster-settings.json`）：

```powershell
.\tools\test-mq.ps1 -ClusterSettingsPath .tools/mq-cluster-settings.json -AllowLegacyBroker -ManualFailover -SkipBuild -SkipBrowser
```

外部模式在检查点提示停止和恢复第一个管理入口对应的节点，由服务器终端执行，再回验收终端按回车；脚本验证实际副本数，不凭按回车认定停机成功。成功仅清理脚本创建的随机 vhost；失败保留证据，并在 finally 提示恢复节点。配置和复现说明见 [可靠派发](reliable-dispatch.md)。

边界：RabbitMQ 3.8.34 使用显式旧版兼容策略，死信转移为 at-most-once，至少一次死信转移尚未验收；该能力从 3.10 才引入，见 [官方说明](https://www.rabbitmq.com/blog/2022/03/29/at-least-once-dead-lettering)。死信目标不可用时的可靠转移未验证。单节点测试在首轮工作完成后停止 Broker，再启动隔离业务进程重跑，验证两节点多数派及地址切换，不等同于在途消息窗口中的无中断连接恢复或 Relay 崩溃恢复。全部 Broker 位于同一宿主机，宿主机故障、多数派丢失、网络分区、V5 真实 MySQL 升级仍未测。常驻业务实例和业务数据库未变更；连接配置在下一次使用新 JAR/IDE 启动服务时读取。浏览器/UI 没有变更，未重跑浏览器验收。

证据（均位于忽略目录，不提交包含密钥的配置、数据库或日志）：

- `.tools/mq-project-verify.log`：根目录 Maven 结果。
- `.tools/mq-e2e/taskflow_it_83189c40477a4d8995c30d86ac3121b2/report.json`：两个阶段各 43 项、版本、兼容策略和故障节点。
- 同目录的 `quorum-before.json`、`quorum-one-node-down.json`、`quorum-restored.json`：同一组队列的三个成员及 3→2→3 在线副本和 leader 变化。
- `.tools/execution-e2e/20261028-101541-037/report.json`、`.tools/execution-e2e/20261028-101902-588/report.json`：正常与停机阶段的 43 项真实进程证据；同目录日志记录分角色账号及 5672→5673 连接。

## 2026-10-27 初始验证结果（历史）

| 验证 | 实际结果 |
| --- | --- |
| 修改前 Cron 定向测试 | PlanTriggerTests、PlanCalculationTests、PlanSyncTests、PlanMigrationTests 共 22 项通过 |
| 最终根目录 mvnw.cmd verify | BUILD SUCCESS；123 项中 118 项通过、5 项按外部环境条件跳过，0 失败、0 错误；三个可执行 JAR 生成 |
| 新增可靠派发测试 | Outbox 10 项、发布确认 3 项、V4→V5 升级 1 项、Worker 消费 7 项，共 21 项通过 |
| 三个真实 Java 进程 | tools/test-execution.ps1 -SkipBuild -SkipBrowser，32 项通过；显式 HTTP 派发、独立 H2 文件库与随机内部密钥 |
| 集群配置 | Compose/YAML、账号哈希、两个 quorum 队列、独立卷与环境变量覆盖已解析检查；PowerShell 三个脚本语法通过，Compose 命令参数以命令替身检查 |
| 真实 MQ、单节点故障 | 未执行；需先搭建三节点集群 |
| V5 真实 MySQL 升级 | 未执行；V4→V5 在隔离 H2 验证 |
| 浏览器/UI | 未重新运行，无前端代码变更 |

5 项条件跳过为真实 Redis 连接 1 项、真实 Redis 缓存 3 项、Admin 专用 MySQL 迁移 1 项。不把跳过项计入通过项。

## Cron 是否确实自动执行

三进程验收通过真实 Admin API 保存启用的 `*/2 * * * * *` Cron 配置，等待异步同步，不调用手动触发接口，随后查询到 CRON Run、原计划时间、配置快照和一次 Attempt，并由真实 Worker 完成执行及结果上报。

另验证了：下一执行时间与同一基准的 Cron/时区预览一致；Scheduler 重启不重新计算既有时间；未到时的每日计划不触发；停用同步完成后不再创建后续自动 Run。定向测试还覆盖并发扫描、原计划轮次恢复后去重、计划推进失败回滚以及 SKIP/FIRE_ONCE。

Cron 自动调度的真实三服务 HTTP 回归通过；测试使用隔离实例，业务环境需另行部署新 JAR。

## 2026-10-27 故障注入与预期（历史）

| 场景与注入方式 | 预期与实际 |
| --- | --- |
| Run/Attempt 写入后令 Outbox 插入失败 | 本地事务全部回滚；通过 |
| 命令准备/计划事务失败 | Attempt 租约、Run 和对应 Outbox 不部分提交；通过 |
| 同一计划时间重扫 | 保留同一个 Run 和一个 Outbox；通过 |
| 持久化 SENDING 后模拟 Relay 退出、使领取租约过期 | 新 Relay 接管，claimVersion 增加，旧领取者更新被拒绝；通过 |
| Mock 发布确认成功后，注入本地成功标记失败 | 保留 SENDING，接管后重发相同事件、消息内容与执行租约；通过 |
| 两个 Relay 并发，发布期间用门闩控制时序 | 只有取得有效领取租约的一方发布；通过 |
| 发布方持续不可用 | 到次数上限进入 FAILED，不新建业务 Attempt；通过 |
| Mock Broker nack、mandatory return 或 confirm 超时 | 都不认为发布成功；通过 |
| Inbox 提交后注入 ACK IOException，再重投相同事件 | Inbox 不重复插入，Handler 只执行一次；通过 |
| 无效版本、格式、标识冲突、过期新命令 | 拒绝消息，不执行 Handler；通过；实际 Broker 死信转移待验证 |
| Worker 接收时数据库短暂/持续不可用 | 恢复后 ACK，或在 4 次后 reject；通过 |
| Attempt 执行租约过期后返回旧结果 | 取消未确认派发，拒绝迟到终态；通过 |

上述 Relay 的“崩溃”通过持久化状态和注入写失败模拟，不是向真实 MQ 发送后杀进程。已有三进程测试另实际停止/重启 Scheduler、Worker 和 Admin，验证历史、未派发工作、配置同步状态及删除墓碑持久化；该阶段进程测试通过的是 HTTP 模式。

回归中发现既有 JobSyncTests 的人工重试测试依赖立即扫描，TIMESTAMP(3) 舍入可能令就绪时间暂时晚于 JVM 时间。测试现在先断言次数归零，再显式推进夹具的待发送时间；没有放宽生产重试逻辑或加入等待来掩盖结果。

## 2026-10-27 本机 Compose 准备记录（历史）

2026-10-27 运行 `tools/mq-cluster.ps1 -Action init`，生成被忽略的随机 cookie、分角色凭据、引导定义、队列拓扑和 Scheduler/Worker 本机 MQ YAML，没有启动 Broker 或升级当前业务实例。

集群脚本先等三个节点在线，再导入三副本队列；避免在首节点引导时创建不足副本的 quorum 队列。配置与命令参数检查通过；该阶段未运行 Docker Compose，镜像启动和节点发现尚未实测。

安装 Docker Compose v2 并准备好资源后，在仓库根目录运行：

```powershell
# 本机 init 已完成，无需重复生成账号或 cookie
.\tools\mq-cluster.ps1 -Action up
.\tools\mq-cluster.ps1 -Action status
.\tools\test-mq.ps1 -SkipBuild -SkipBrowser
```

test-mq 预期检查三个在线节点和三副本 quorum，创建随机 taskflow_it_ vhost，验证真实 MQ 三服务执行和重复事件，再停止 rabbit1 重跑整个闭环，最后恢复节点。测试成功仅清理该测试 vhost；失败保留证据。多数派丢失、跨主机故障、真实 Relay 崩溃窗口及 V5 MySQL 升级仍应补充实测记录。

## 证据位置

本机日志位于被忽略的 `.tools/cron-baseline.log`、`.tools/mq-final-verify.log`、`.tools/mq-cron-process-verification.log`。三进程证据位于 `.tools/execution-e2e/20261027-092146-374/`，含 report.json、隔离服务 YAML 和日志。各模块 target/surefire-reports 保存 JUnit 结果。

日志和测试数据库不打包到业务 JAR，含随机密钥的本机 YAML、MQ .env 和 runtime 定义不提交仓库。后续真实 MQ 证据由 tools/test-mq.ps1 写入 `.tools/mq-e2e/` 和对应三进程目录。

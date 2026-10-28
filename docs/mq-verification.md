# Cron 复测与可靠派发验证记录

验证日期：2026-10-27。代码及三节点集群部署配置已完成；本机没有 Docker、RabbitMQ 或 Erlang，真实 Broker 尚未启动。本记录区分进程实测、数据库集成测试和 MQ 边界故障注入，不将 Mock 确认当作集群验收。

## 本轮结果

| 验证 | 实际结果 |
| --- | --- |
| 修改前 Cron 定向测试 | PlanTriggerTests、PlanCalculationTests、PlanSyncTests、PlanMigrationTests 共 22 项通过 |
| 最终根目录 mvnw.cmd verify | BUILD SUCCESS；123 项中 118 项通过、5 项按外部环境条件跳过，0 失败、0 错误；三个可执行 JAR 生成 |
| 新增可靠派发测试 | Outbox 10 项、发布确认 3 项、V4→V5 升级 1 项、Worker 消费 7 项，共 21 项通过 |
| 三个真实 Java 进程 | tools/test-execution.ps1 -SkipBuild -SkipBrowser，32 项通过；显式 HTTP 派发、独立 H2 文件库与随机内部密钥 |
| 集群配置 | Compose/YAML、账号哈希、两个 quorum 队列、独立卷与环境变量覆盖已解析检查；PowerShell 三个脚本语法通过，Compose 命令参数以命令替身检查 |
| 真实 MQ、单节点故障 | 未执行；需先搭建三节点集群 |
| V5 真实 MySQL 升级 | 未执行；本轮 V4→V5 在隔离 H2 验证 |
| 本轮浏览器/UI | 未重新运行，无前端代码变更 |

5 项条件跳过为真实 Redis 连接 1 项、真实 Redis 缓存 3 项、Admin 专用 MySQL 迁移 1 项。不把跳过项计入通过项。

## Cron 是否确实自动执行

三进程验收通过真实 Admin API 保存启用的 `*/2 * * * * *` Cron 配置，等待异步同步，不调用手动触发接口，随后查询到 CRON Run、原计划时间、配置快照和一次 Attempt，并由真实 Worker 完成执行及结果上报。

另验证了：下一执行时间与同一基准的 Cron/时区预览一致；Scheduler 重启不重新计算既有时间；未到时的每日计划不触发；停用同步完成后不再创建后续自动 Run。定向测试还覆盖并发扫描、原计划轮次恢复后去重、计划推进失败回滚以及 SKIP/FIRE_ONCE。

因此，Cron 自动调度已实现并完成真实三服务 HTTP 回归。README、架构与执行协议中此前“尚未实现”的描述已修正；这不等于本机现有业务实例已部署新 JAR。

## 故障注入与预期

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

上述 Relay 的“崩溃”通过持久化状态和注入写失败模拟，不是向真实 MQ 发送后杀进程。已有三进程测试另实际停止/重启 Scheduler、Worker 和 Admin，验证历史、未派发工作、配置同步状态及删除墓碑持久化；本轮通过的是 HTTP 模式。

回归中发现既有 JobSyncTests 的人工重试测试依赖立即扫描，TIMESTAMP(3) 舍入可能令就绪时间暂时晚于 JVM 时间。测试现在先断言次数归零，再显式推进夹具的待发送时间；没有放宽生产重试逻辑或加入等待来掩盖结果。

## 待真实集群验收

本轮已运行 `tools/mq-cluster.ps1 -Action init`，生成被忽略的随机 cookie、分角色凭据、引导定义、队列拓扑和 Scheduler/Worker 本机 MQ YAML，没有启动 Broker 或升级当前业务实例。

集群脚本先等三个节点在线，再导入三副本队列；避免在首节点引导时创建不足副本的 quorum 队列。已检查配置与命令参数，但 Docker Compose 本身尚未运行，不能声称镜像启动和节点发现已实测。

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

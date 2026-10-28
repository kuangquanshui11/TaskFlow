# Worker 满载背压与死信边界验证

日期：2026-10-28。范围为 Scheduler 满载派发检查和 Worker MQ 接收背压，不增加服务间协议字段、数据库迁移或中间件。

## 行为

- Scheduler 观察到目标 Worker 的 `runningCount + pendingCount >= maxPending` 时，沿用已有资格等待机制推迟派发准备和 Outbox 发布；未领取的 Attempt 不开始执行租约、不消耗派发次数。
- 心跳负载不是容量预订，无法消除在途竞争。Worker 的 `429 / WORKER_BUSY` 保留当前 delivery 并按配置间隔等待容量，不增加或重置实际故障预算，不主动反复重新入队。单消费者与 prefetch=1 将后续积压留在 Broker。
- 容量恢复后提交同一 Attempt 的 Inbox，再 ACK；不变更原消息、租约、Run 或 Attempt 身份。
- 合法命令的租约过期时，ACK 清理失效投递，不写入新 Inbox、不执行 Handler、不进入死信。Scheduler 数据库中的原 Attempt 仍由租约扫描收尾为超时；ACK 不表示任务成功。
- 真实数据库故障继续使用有限预算；非法事件、冲突内容等仍隔离至死信。ACK 失败不在本地重试，Broker 重投后按原 Attempt 去重。容量等待被中断时保留中断标记并重新入队；连接关闭时不再写 Inbox 或确认旧 channel 的消息。

## 本地回归

```powershell
.\mvnw.cmd -pl taskflow-scheduler,taskflow-worker verify
```

| 验证 | 结果 |
| --- | --- |
| Scheduler 完整回归，隔离 H2 | 83 项通过，0 失败、0 错误 |
| Worker 完整回归，隔离 H2 | 42 项通过，0 失败、0 错误；真实 Broker 专项 1 项按环境条件跳过，另行执行 |
| Scheduler / Worker 可执行 JAR | 打包成功 |
| MQ 验收 PowerShell 语法 | 通过 |

Worker 新增的 5 项测试覆盖：真实容量满后等待超过原故障预算并恢复、等待期间租约过期、容量等待不增加或清零数据库故障预算、关闭 channel、等待中断。既有过期命令测试改为断言 ACK 且不执行、不转死信；既有入库后 ACK、ACK 丢失去重、冲突消息与数据库故障预算测试继续回归。

Scheduler 新增 1 项测试覆盖：满载仍 ONLINE 但不可派发、首次准备保持 READY/WAITING 且无租约、心跳恢复后准备原 Attempt、发布前再次满载不消耗发布预算、再恢复后发布原消息并保持原租约版本。

证据：`.tools/capacity-backpressure-verify.log`、两个模块的 `target/surefire-reports`。测试使用独立 YAML 和随机 H2 数据库，不连接业务数据库。

## 真实 RabbitMQ

`tools/test-mq.ps1` 在自己创建的随机 `taskflow_it_<UUID>` vhost 中、完成三副本拓扑检查后，先运行 `DispatchBackpressureBrokerTests`，再运行原三服务闭环。只有脚本传入测试专用环境变量并匹配隔离 vhost 时才启用 Broker 测试，脚本明确检查执行 1 项、跳过 0 项，结束恢复环境变量。连接参数统一放在 `application-test-backpressure-mq.yml`，凭据不进入版本库。

```powershell
# 外部集群连接文件与凭据保存在被忽略的本机配置中。
.\tools\test-mq.ps1 -SkipBuild -SkipBrowser -SkipFailover `
    -ClusterSettingsPath <本机集群连接文件> -AllowLegacyBroker
```

Broker 专项使用真实 Spring AMQP listener、手动 ACK、prefetch=1 和 Worker 独立 H2 Inbox：填满本地容量后投递消息，确认等待超过 4 次仍未返回、Inbox 尚无新记录且死信队列为空；释放一个容量后接收原 Attempt；再次投递相同事件仍只保留一条 Inbox；首次投递已过期事件也不新增 Inbox，最终死信队列仍为空。

本轮使用远程 RabbitMQ 3.8.34 三节点、三副本 quorum 测试队列：

| 验证 | 结果 |
| --- | --- |
| 真实 Broker 满载背压专项 | 1 项通过，0 失败、0 错误、0 跳过；满载/过期处理后死信队列为空 |
| 真实三服务 MQ 闭环 | 43 项通过，包含执行、业务重试、重复事件、配置同步、Cron、服务重启恢复与节点检查 |
| 隔离资源清理 | 测试进程退出，本轮随机测试 vhost 删除成功 |

证据：`.tools/capacity-backpressure-mq.log`、`.tools/mq-e2e/taskflow_it_85647b60a6ac4e34819b698a982b5c1c/report.json` 及同目录 `backpressure.log`、`.tools/execution-e2e/20261028-113322-031/report.json`。日志和含凭据的测试配置保持忽略，不纳入提交。

## 边界

此次不自动重启常驻应用、不更新业务数据库、不改远程 Broker 参数或业务队列策略。验收只使用自己的随机测试 vhost 和数据库，不进行 Broker 停机注入；成功后脚本仅删除本轮测试 vhost。

心跳 gating 不等于容量预订，已准备和已发布消息仍受原执行租约约束。没有实现无限等待、自动续租或执行成功保证。Broker 的消费确认超时需要覆盖允许的最长在途租约并留出数据库 I/O 余量；连接故障及 Broker 的 delivery-limit 等策略是独立边界。当前远程 3.8.34 的旧版死信转移保障不因本修复改变，见 [可靠派发](reliable-dispatch.md) 和 [MQ 验收](mq-verification.md)。

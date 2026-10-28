# taskflow-worker

任务执行服务。负责白名单 Handler、执行并发、心跳和结果上报；不直接读写其他服务的表。

## 当前实现

已实现独立 MySQL / Flyway V1、持久化 Inbox、幂等接收、有界线程执行、超时中断、结果持久化与有限重报。Controller 调用 IExecutionService / ExecutionServiceImpl，ExecutionMapper 只读写 Worker 自己的表，SchedulerReportClient 在本地事务外上报结果。

结果保存已拆为 IExecutionResultService / ExecutionResultServiceImpl：对同一份不可变结果有限重试，默认总共 5 次、间隔 500ms，保留原执行槽位，不重新执行 Handler。保存恢复后继续原结果上报；耗尽、冲突和中断有结构化错误及 Micrometer 计数。线程池拒绝已领取任务时保存 EXECUTOR_REJECTED 终态。耗尽或进程崩溃仍可能遗留本地 RUNNING，重启按副作用未知处理；配置、故障边界及验证见 [结果保存恢复](../docs/worker-result-recovery.md)。

dispatch 模块默认从 RabbitMQ 固定 Worker quorum 队列接收 v1 事件，校验消息标识及完整 DTO，复用本地 Inbox 持久化后才手动 ACK；重复事件沿用原记录，永久错误或有限接收重试耗尽进入死信队列。三节点集群配置已提供，真实 Broker 验收待执行，见 [可靠派发](../docs/reliable-dispatch.md)。一个固定队列仍只对应同一 Worker 身份、一个执行进程与其持久化数据库，当前不支持其他 Worker 数据库竞争接管此队列。

内置白名单 demoSuccess、demoFail、demoSlow、demoFailOnce。demoSlow 可用 payload.durationMillis（0–60000）控制耗时；demoFailOnce 首次失败，可由 Scheduler 按快照策略创建下一 Attempt。重启保留 ACCEPTED 工作，将之前 RUNNING 的未知执行转为 WORKER_RESTARTED 失败，不盲目重做外部副作用。

默认需要 taskflow_worker schema 和独立账号，一个 Worker 数据库对应一个执行进程。本机可先运行 tools/prepare-local-execution.ps1，再使用 tools/services.ps1 -Action start。实际凭据在忽略的本机 YAML，参数统一位于 application.yml。node 模块已实现按进程会话注册、实际 Handler 能力与执行负载上报、独立线程每 5 秒心跳，失败后在下一周期使用原会话重试。独立节点密钥由 tools/prepare-node-identity.ps1 创建，并由 Scheduler/Worker 共同读取。重启会话需等待旧会话过期，执行租约续期与跨节点接管尚未实现，见 [节点协议](../docs/worker-nodes.md)。

## 本地启动

在仓库根目录执行：

```powershell
.\mvnw.cmd -pl taskflow-worker spring-boot:run
```

默认端口 8082，可用 TASKFLOW_WORKER_PORT 环境变量覆盖。健康检查：http://localhost:8082/actuator/health。

## 开发规则

功能按业务能力组织，内部使用 controller、service、service/impl、mapper：Controller 按职责拆类，调用 Service 接口；实现类在 service/impl 中 implements 对应接口，通过 Mapper 访问本服务数据。不得添加另外两个服务的 Maven 依赖，不共享实体或数据访问实现。

完整边界见 [架构文档](../docs/architecture.md)，实施顺序见 [开发计划](../docs/development-plan.md)。

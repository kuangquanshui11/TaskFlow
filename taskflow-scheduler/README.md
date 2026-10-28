# taskflow-scheduler

调度与运行生命周期服务。拥有计划副本、Run/Attempt、派发和恢复逻辑；不包含实际业务 Handler。

已实现版本化配置同步、计划计算、到期扫描和自动执行。V3 保存下一计划时间；V4 保存 Run 触发来源和计划唯一键。触发事务创建 Run/首次 Attempt 并推进计划，支持 SKIP/FIRE_ONCE；计算、触发、派发分别使用独立线程，规则见 [计划计算](../docs/plan-calculation.md)、[自动调度](../docs/automatic-scheduling.md)。

## 当前实现

已实现独立 MySQL / Flyway V1、Run/Attempt 持久化、请求幂等、任务完整快照、待派发扫描、Worker HTTP 派发、有限传输重试、有限业务重试、执行租约校验和终态查询。Controller 接收内部协议，IRunService / RunServiceImpl 编排事务和派发，RunDataMapper 仅访问 Scheduler 自己的数据库。事务内不等待 Worker HTTP。

默认需要 taskflow_scheduler schema 和独立账号。本机可运行 tools/prepare-local-execution.ps1 准备，再用 tools/services.ps1 -Action start 后台启动。实际凭据在忽略的 config/taskflow-scheduler-local.yml 与 config/taskflow-internal-local.yml 中，参数统一位于 application.yml。

已增加 dispatch 业务模块和 Flyway V5：Run/Attempt 与 WAITING Outbox 同事务提交，派发扫描准备固定消息与租约，独立 Relay 在事务外经 RabbitMQ 发布，检查 correlated confirm 和 mandatory return，有限退避重试并对过期领取者作版本校验。默认 transport 为 rabbitmq，http 为显式兼容通道。三节点部署与切换见 [可靠派发](../docs/reliable-dispatch.md)，实际验证边界见 [MQ 验证](../docs/mq-verification.md)。自动触发默认开启，可通过 TASKFLOW_PLAN_TRIGGER_ENABLED 暂停本节点扫描。

node 模块与 Flyway V6 已实现 Worker 注册、独立密钥验证、会话历史、心跳序号、超时离线与分页查询，默认以最后有效心跳后 30 秒为边界。派发和 Relay 检查固定目标在线及能力/路由；无有效节点时新 Attempt 等待，不提前消耗租约。先运行 tools/prepare-node-identity.ps1，再按 [节点协议](../docs/worker-nodes.md) 升级。执行租约续期及跨节点回收尚未实现。

资格不满足的 Attempt / Outbox 会持久化推迟下一次检查，默认 2s，可通过 `taskflow.dispatch.node-recheck-delay` / `TASKFLOW_NODE_RECHECK_DELAY` 配置，范围 100ms–5m。HTTP、MQ 准备和 MQ Relay 都执行此规则，过期 SENDING 也遵守下次检查时间；等待不增加次数或延长租约。超过 100 条阻塞记录的验收与边界见 [扫描公平性验证](../docs/dispatch-fairness-verification.md)。

## 本地启动

在仓库根目录执行：

```powershell
.\mvnw.cmd -pl taskflow-scheduler spring-boot:run
```

默认端口 8081，可用 TASKFLOW_SCHEDULER_PORT 环境变量覆盖。健康检查：http://localhost:8081/actuator/health。

## 开发规则

功能按业务能力组织，内部使用 controller、service、service/impl、mapper：Controller 按职责拆类，调用 Service 接口；实现类在 service/impl 中 implements 对应接口，通过 Mapper 访问本服务数据。不得添加另外两个服务的 Maven 依赖，不共享实体或数据访问实现。

完整边界见 [架构文档](../docs/architecture.md)，实施顺序见 [开发计划](../docs/development-plan.md)。

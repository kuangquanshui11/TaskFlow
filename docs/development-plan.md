# TaskFlow 分阶段实施与验收

我们以 Admin、Scheduler、Worker 三个业务服务为核心，按工程边界、任务管理、执行、调度、可靠性和运维划分实施阶段。每个阶段独立验收，架构约束见根目录 AGENTS.md 和 [架构文档](architecture.md)。

平台治理采用独立的 Gateway、Registry、Config Server，六个应用分别打包。平台已接入 Spring Cloud 2025.1.4、Eureka、Config Server（native/Git）、OpenFeign/LoadBalancer、Resilience4j 和 Micrometer Tracing/OTLP。独立开发可使用直连模式。配置与实测边界见 [技术栈](microservice-platform.md) 与 [平台验证](microservice-platform-verification.md)。

## 当前阶段

| 阶段 | 当前状态 | 剩余范围 |
| --- | --- | --- |
| 1. 工程边界与启动骨架 | 完成。各服务独立启动、打包和验证 | |
| 2. Admin 任务定义持久化 | 完成。Job 管理、版本校验、软删除和真实控制台已接入 | |
| 3. 手动执行的跨服务闭环 | 完成。手动触发、执行、结果查询和重启恢复已有验收记录 | |
| 4. Cron 与计划同步 | 部分完成。定时触发、去重、Outbox 配置推送、按 Job ID 的双向全量对账和维护恢复已实现 | P03 全局配置事件游标的增量追赶，未实现；见 [设计与验收范围](config-reconciliation.md#配置事件增量追赶p03未实现) |
| 5. 可靠消息与恢复 | 可靠派发和租约恢复已实现，已有 RabbitMQ 三节点历史验收 | 真实 Broker 的死信可靠转移、更多故障窗口和异步追踪 |
| 6. 运维与交付 | 登录、运行诊断、端到端统计、操作审计和告警已实现 | 空环境部署、同版本 MySQL 与 MQ 联合回归、多节点容量基线和目标环境联调 |

验证记录按实际日期、版本和环境解读。当前未完成项及验收条件见 [剩余待办](../尚未完成的功能.md)。

## 第一步 工程边界与启动骨架

阶段范围：

- 根 POM 改为 Maven 聚合项目。
- Admin、Scheduler、Worker 分别具有启动类、配置、测试和可执行 JAR。
- 现有控制台和 /api/v1/hello 迁入 Admin。
- 默认端口依次为 8080、8081、8082，通过独立环境变量覆盖。
- 三个服务分别扫描各自的包，应用模块之间保持 Maven 依赖隔离。骨架阶段的验收无需数据库或 MQ。
- 健康检查与管理端资源归属使用实际 HTTP 测试验证。

验收：

1. 根目录执行 ./mvnw.cmd verify，三个服务测试通过并分别生成可执行 JAR。
2. 三个 JAR 同时启动，各自 /actuator/health 返回 UP。
3. Admin 的首页和 hello 接口可用；Scheduler、Worker 不暴露管理台和 hello。
4. 停止一个服务后，其余服务的独立健康检查仍可访问。

三个 JAR 的独立进程与健康检查已通过骨架验收。历史测试结果见 [第一步验证记录](verification.md)。

## 第二步 Admin 任务定义持久化

我们将任务定义管理限定在 taskflow-admin，使配置管理可以独立于调度和执行服务运行。

Admin 使用 MyBatis-Plus / Flyway，提供任务管理与辅助查询接口；持久层通过独立 Entity、Mapper 和 Repository 适配访问数据库，提供版本校验与软删除。本机 taskflow_admin 已核对 V1、建立基线并完成 V2 软删除迁移，真实查询接口已验证。独立 Vue 控制台已接入真实 API，并通过 Nginx 托管；接口动作路径、参数和状态见 [接口文档](api.md)，部署见 [前端部署](frontend-deployment.md)。

1. 定义创建、修改、查询、启停 Job 的 API 和错误响应。
2. 接入 MyBatis-Plus、MySQL 与 Flyway，数据库和迁移仅归属 Admin。
3. 建立 job_definition 表，保存 name、handler_key、payload_json、enabled、config_version 等字段；Cron、时区和执行策略按阶段扩展。
4. 按业务能力组织 job/controller、job/service、job/service/impl、job/mapper；请求响应、业务模型、持久化实体与配置分别放 dto、model、entity、config。Controller 按功能拆类，Service 实现通过 implements 对应接口。
5. 加入必填校验、JSON 校验、分页、并发修改版本检查；禁止直接绑定数据库实体作为 HTTP 入参。
6. 覆盖空库迁移、创建、错误输入、版本冲突、启停和查询。
7. 接入现有任务定义页面，以真实 API 替换该页面演示数据。

验收：创建任务后可查询、修改和停用，重启服务数据仍在；不依赖 Scheduler、Worker 也能完成任务配置管理。

实现前检查本机 MySQL / Docker 实际可用情况。没有数据库连接时先准备配置、迁移和集成测试环境，将数据库测试标记为未执行。

## 第三步 手动执行的跨服务闭环

我们仅为 Admin 任务详情引入 Redis 查询缓存，提供提交后失效与 Redis 故障降级，见 [访问与缓存流程](request-flow.md) 和 [缓存验证记录](cache-verification.md)。默认 TTL 60 秒，分页和统计不缓存；缓存不承担跨服务状态管理。手动执行闭环的协议与验收见 [执行协议](execution-protocol.md)、[执行验证](execution-verification.md)。

1. 定义内部协议、幂等键、配置快照、错误分类和服务身份校验。
2. Scheduler 独立接入数据库，维护 Run / Attempt 和待派发扫描。
3. Admin 发出手动触发请求，由 Scheduler 创建 Run；HTTP 超时可用同一幂等键查询或重试。
4. Worker 提供白名单 Handler，先实现成功、失败和可控耗时任务。
5. Worker 以幂等接收和异步执行处理派发，向 Scheduler 上报结果。
6. Admin 通过 Scheduler 查询运行状态和尝试历史。
7. 在管理台接入手动触发和执行详情。

验收：运行三个服务，创建任务、触发、查询成功和失败结果；重复提交同一请求不会生成第二个逻辑 Run；进程重启后历史仍在。

Scheduler 的 Run/Attempt、Worker 的去重 Inbox 和待上报结果分别持久化。执行已接入有限业务重试、执行资源上限、超时中断与租约校验。手动执行已在隔离三进程中通过真实 HTTP 与文件数据库重启验证。 [执行验证](execution-verification.md)

## 第四步 Cron 与计划同步

Cron 调度包含配置同步、计划计算、到期扫描及自动执行闭环：Scheduler 在本地事务中创建 Run/首次 Attempt 并推进计划，数据库唯一键保护同一 Job/计划时间；错过执行按宽限和 SKIP/FIRE_ONCE 处理。管理台展示真实计划、节点触发开关和运行来源。协议见 [异步配置同步](config-sync.md)、[计划计算](plan-calculation.md)、[自动调度](automatic-scheduling.md)。

- 我保留按事件标识及单 Job 版本推送。Admin 按 Job ID 执行有界的自动双向全量对账，识别孤立副本和同版本快照差异，支持人工补发与维护窗口内的副本及 Inbox 重建。见 [配置对账](config-reconciliation.md)。
- P03 全局配置事件游标的增量追赶尚未实现。我将提交顺序与安全水位、持久化消费断点、缺口检测和保留期外恢复作为剩余范围；Job ID 续扫进度和逐事件发送确认不作为其完成依据。设计及验收场景见 [P03](config-reconciliation.md#配置事件增量追赶p03未实现)，待办保留在 [剩余待办](../尚未完成的功能.md)。
- 已实现 Scheduler 版本副本、时区和 next_fire_time；重复/旧事件不移动时间，查询和重启不重新计算。
- 已实现到期扫描、计划唯一键、SKIP / FIRE_ONCE，触发、补算和派发线程隔离。
- 已验证并发扫描、原到期时间恢复后的重扫、事务失败恢复、错过策略和真实三服务自动执行。

已有能力的验收：同一 Job 的同一计划时间只生成一个 Run；配置同步失败可发现并补偿，页面能区分保存与生效。阶段整体完成还需独立通过 P03 的增量追赶验收。

## 第五步 可靠消息与恢复

我将任务派发与配置同步分别交给 Scheduler 派发 Outbox 和 Admin 配置 Outbox。任务默认经 RabbitMQ 派发，HTTP 可显式选择。Scheduler 用发布确认和不可路由回执判断 Broker 是否接收，Worker 在 Inbox 提交后手动 ACK，并按有限重试和死信策略处理消费失败。仓库提供三节点 quorum 集群配置及隔离 vhost 验收脚本。 [可靠派发](reliable-dispatch.md)

Scheduler 根据节点能力和负载选择 Worker，并为每个 Attempt 固定目标队列。Worker 通过独立注册与心跳协议上报节点状态，执行中可有界续租。租约失效后，Scheduler 在重试预算内为原 Run 创建新 Attempt 并重新选节点，旧执行者的迟到结果受租约与状态校验。同一 Attempt 跨 Inbox 接管留待有明确需求时评估。Worker 已支持执行结果保存重试、恢复上报及耗尽诊断。 [节点管理](worker-nodes.md) · [执行租约](execution-leases.md) · [水平扩展](worker-horizontal-scaling.md) · [结果保存恢复](worker-result-recovery.md)

2026-10-28，RabbitMQ 3.8.34 远程集群在三个节点正常和单节点停机时均通过应用执行验收。该历史环境使用旧版兼容策略。后续故障专项另有 Relay 或 Worker 退出、迟到上报、连接中断及未 ACK 重投的验证记录。 [MQ 历史验收](mq-verification.md) · [派发故障窗口验证](dispatch-fault-verification.md)

阶段尚待验收的范围包括隔离新版 RabbitMQ 集群中的至少一次死信转移、发布确认期间节点退出、多数派丢失与恢复，以及消息重投和 Worker 重启补报后的 Trace 关联。验收需保存故障注入、实际结果及 Broker、数据库和运行记录证据。 [剩余待办](../尚未完成的功能.md) · [P05 验证](p05-verification.md)

## 第六步 运维与交付

运行查询边界。Scheduler 读取本服务的权威 Run 和 Attempt 数据。Admin 校验 `run:read` 和查询参数，在本地 SQL 事务外通过版本化 HTTP 代理；Scheduler 在内部入口独立校验。全局列表默认最近 24 小时，显式窗口最长 7 天，每页最多 100 条、最多第 10000 页。省略时间的历史查询须指定精确 Run ID。统计沿用相同时间窗约束，单任务历史沿用按 Job ID 分页的契约。见 [全局运行查询与详情](global-run-query.md)。

运行详情已按 Attempt 提供执行时间线。Admin 在 `run:read` 权限下查询，单次最多读取两个来源各 100 条事件。Worker 暂不可用时，详情继续展示 Scheduler 的派发、租约和权威终态记录。见 [Attempt 执行事件](attempt-events.md)。

全局运行列表已实现：Scheduler 独立查询、Admin run:read 代理、控制台 `/runs` 提供时间/状态/任务 ID/触发来源交集筛选及精确 Run ID 定位。独立运行详情 `/runs/:runId` 从全局列表与单任务运行结果进入，展示配置快照、Attempt 历史、结果码、目标 Worker 和派发起止时间。范围见 [全局运行查询与详情](global-run-query.md)，验证见 [全局列表验证](global-run-query-verification.md) 和 [运行详情验证](run-detail-verification.md)。

工作台的失败总数和失败 Top 5 可跳转到全局运行列表。总数入口保留统计时间窗与失败状态，分组入口再带入任务 ID 与结果码。可打开单条运行详情并带着筛选条件返回。见 [运行统计设计](run-reporting.md)。

运行详情支持对各次 Attempt 均确定未启动 Handler 的失败 Run 进行受控重跑。Admin 校验操作权限和当前 Job 版本，Scheduler 创建新 Run 并在同一事务记录原 Run、操作者及受理时间。控制台可用原请求键重试未确认的提交。见 [失败 Run 的受控重跑](global-run-query.md#失败-run-的受控重跑)。

我在运行详情提供持久化的 Trace ID 与阶段耗时，在工作台使用从 Run 创建到终态的端到端 P95。Admin 审计 Job 新增、修改、启停、删除及普通手动触发，查询要求 `audit:read`。结构化日志以 Run ID、Attempt ID 和 Trace ID 关联运行。2026-11-16 的隔离进程专项验证了 Gateway、Admin、Scheduler 和 Worker 在真实 HTTP 派发链路上导出同一 Trace，也验证了审计查询与控制台显示。真实 RabbitMQ 链路及 Jaeger 界面仍待验收。 [运行统计](run-reporting.md) · [操作审计](operation-audit.md) · [P05 验证记录](p05-verification.md)

平台治理包含：Nginx → Gateway → Admin，内部服务名发现与 Feign 调用、熔断及同步 HTTP 链路追踪；注册/配置中心独立认证、非秘密策略仓库及可选 Jaeger Compose 已提供。默认配置变更重启生效，不提供完整热刷新；Eureka/Config 单实例，HA 和真实 Jaeger 容器验收仍未完成。

- S1 提供 Scheduler 时间窗统计、Admin `run:read` 代理、工作台运行概览和失败 Top 5，见 [统计设计](run-reporting.md) 与 [统计验证](run-reporting-verification.md)。S2 提供待发布积压、Attempt 派发逾期与租约到期、心跳年龄、Worker 待上报与执行积压的瞬时采样，见 [指标字典](observability-metrics.md) 与 [采样验证](observability-metrics-verification.md)。
- S3 告警已扩展到多 Scheduler 和 Worker。Prometheus 评估终态业务 Run 失败、Worker 离线、采样中断和派发积压，双 Alertmanager 负责 Webhook 分组通知。目标环境的真实 RabbitMQ 队列切换和 Webhook 联调仍待验收。 [多节点告警](scheduler-alerts.md)
- Keycloak 用户登录、JWT 验证、管理权限、Redis 30 分钟滑动会话及入口限流已实现，见 [登录与入口安全](access-security.md)。2026-10-28 安全验证时 Redis 不可达，真实 Redis 会话与限流的后续实测见 [带登录的链路验证](authenticated-regression-verification.md)。
- 平台镜像、Compose 模板、启动说明和 API 示例已提供。 [平台部署](microservice-platform.md) · [运行说明](../README.md) · [API 约定](api.md)

交付验收还需从空环境按 README 复现部署与完整流程，在同一版本执行真实 MySQL 和 RabbitMQ 联合回归，并按目标拓扑建立多 Scheduler、多 Worker 的吞吐与延迟基线。目标环境另需核对入口安全、注册与配置高可用、Trace 持久化和告警接收。 [交付与环境验收待办](../尚未完成的功能.md)

## 每次开发的完成条件

- 功能归属清楚，不跨服务引用实现或读取其他服务的表。
- 对外协议与失败语义有记录。
- 新增数据库结构有服务自己的版本化迁移。
- 变更涉及的测试通过，未完成的外部环境检查明确标注。
- README 和当前阶段状态与实际代码一致。

## 独立业务接入：订单与库存

销售日报第一版在订单服务 report 包实现，包含店铺/商品成交快照、可重复样例导入、按付款日期统计及日报幂等保存；Worker salesReport 经 HTTP 接入，Admin 提供 run:read 查询代理，控制台可查看明细和下载 CSV。默认日期绑定首次受理时间，晚到数据不覆盖既有日报，完整边界见 [销售日报](sales-reports.md)。

我在订单服务的 inventory 包接入样例仓库快照和带 Bearer 鉴权的 WMS 全量 HTTP 来源，按来源版本写入独立库存镜像。Worker 通过 `inventorySync` 按 Run 键调用订单服务，控制台按配置开放模板。2026-11-10 已使用模拟 WMS 在 H2 和 MySQL 验证鉴权、版本保护和重放。目标环境仍需用真实测试仓库核对鉴权协议、仓库与 SKU 映射、数据口径，以及定时派发与故障恢复。 [仓库库存快照同步](inventory-sync.md)

我将过期数据清理限定为订单服务的库存缓存投递成功历史。订单服务固定保留截止时间并保存批次结果，Worker 按 Run 重放安全批次，控制台默认以停用任务预览。 [过期数据清理](expired-data-cleanup.md)

我用独立订单示例检验平台与业务的边界：订单服务保证下单条件扣减、请求幂等、付款与关单互斥、预占释放和流水事务；Worker 通过注册表接入可选订单 Handler，并将有限批量处理结果交给现有 Run/Attempt 重试链路。订单付款期限由订单服务创建时固化，调度只决定检查频率。

当前支持单 SKU 订单、MySQL 权威库存及 Redis 版本快照。订单服务可选接入微信 Native 支付、支付通知和主动查单，迟到款进入全额退款流程。仓储预占释放采用可选的持久化补偿协议。真实商户和仓储接收端仍需部署联调，Redis 前置预扣按需评估。 [支付设计与接入](wechat-native-payment.md)

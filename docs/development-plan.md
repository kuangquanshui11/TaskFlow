# TaskFlow 分阶段实施与验收

我们以 Admin、Scheduler、Worker 三个业务服务为核心，按工程边界、任务管理、执行、调度、可靠性和运维划分实施阶段。每个阶段独立验收，架构约束见根目录 AGENTS.md 和 [架构文档](architecture.md)。

平台治理采用独立的 Gateway、Registry、Config Server，六个应用分别打包。Cloud 2025.1.4、网关公共路由与隔离、Eureka、Config native/Git、OpenFeign/LB、Resilience4j、Micrometer OTel/OTLP 已实现；直连模式用于独立开发。具体配置和实测边界见 [技术栈](microservice-platform.md) 与 [平台验证](microservice-platform-verification.md)。下方测试数量和能力范围对应各阶段的历史验收，验证结果按各记录的日期和环境解读。

## 第一步 工程边界与启动骨架

阶段范围：

- 根 POM 改为 Maven 聚合项目。
- Admin、Scheduler、Worker 分别具有启动类、配置、测试和可执行 JAR。
- 现有控制台和 /api/v1/hello 迁入 Admin。
- 默认端口依次为 8080、8081、8082，通过独立环境变量覆盖。
- 三个服务仅扫描各自包，当前互不依赖，也不依赖数据库或 MQ。
- 健康检查与管理端资源归属使用实际 HTTP 测试验证。

验收：

1. 根目录执行 ./mvnw.cmd verify，三个服务测试通过并分别生成可执行 JAR。
2. 三个 JAR 同时启动，各自 /actuator/health 返回 UP。
3. Admin 的首页和 hello 接口可用；Scheduler、Worker 不暴露管理台和 hello。
4. 停止一个服务后，其余服务的独立健康检查仍可访问。

状态：已完成，7 项测试和三个 JAR 的独立进程验证通过，详细证据见 [第一步验证记录](verification.md)。工程骨架验收不包含任务创建或执行。

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

状态：已完成。Scheduler 的 Run/Attempt、Worker 的去重 Inbox 和待上报结果分别持久化；已加入有限业务重试、执行资源上限、超时中断与租约校验。根目录 65 项测试通过，9 项前端单元测试与 8 项浏览器测试通过，隔离三进程的真实 HTTP 与文件数据库重启验证通过。本机三个服务已使用独立配置运行。

## 第四步 Cron 与计划同步

Cron 调度包含配置同步、计划计算、到期扫描及自动执行闭环：Scheduler 在本地事务中创建 Run/首次 Attempt 并推进计划，数据库唯一键保护同一 Job/计划时间；错过执行按宽限和 SKIP/FIRE_ONCE 处理。管理台展示真实计划、节点触发开关和运行来源。协议见 [异步配置同步](config-sync.md)、[计划计算](plan-calculation.md)、[自动调度](automatic-scheduling.md)。

- 已实现按事件标识及单 Job 配置版本的推送协议、停用/删除语义和同步状态；新增包含墓碑的 Admin 配置游标遍历、实际版本对账及受控补发，见 [配置对账](config-reconciliation.md)。全局事件游标、未知孤立副本发现及自动定时全量扫描尚未实现。
- 已实现 Scheduler 版本副本、时区和 next_fire_time；重复/旧事件不移动时间，查询和重启不重新计算。
- 已实现到期扫描、计划唯一键、SKIP / FIRE_ONCE，触发、补算和派发线程隔离。
- 已验证并发扫描、原到期时间恢复后的重扫、事务失败恢复、错过策略和真实三服务自动执行。

验收：同一 Job 的同一计划时间只生成一个 Run；配置同步失败可发现并补偿，页面能区分保存与生效。

## 第五步 可靠消息与恢复

当前已实现 Scheduler 派发 Outbox、RabbitMQ 发布确认/不可路由处理、Worker Inbox 提交后消费确认、有限接收重试与死信策略；提供三节点 quorum 集群、独立数据卷、多节点连接及隔离 vhost 验收脚本。2026-10-28 已接入远程真实三节点 Broker，正常和单节点停机场景各 43 项三服务检查通过，确认 quorum 选主、两节点执行及恢复三个在线副本。当前 3.8.34 使用显式兼容策略，至少一次死信转移、在途 Broker 节点故障恢复、多数派丢失等仍未验收。协议见 [可靠派发](reliable-dispatch.md)，结果见 [MQ 验证](mq-verification.md)。固定 Worker 有界续租已实现，见 [执行租约](execution-leases.md)；MQ 独立队列水平扩展、固定 Attempt 目标及租约失效后有限跨节点重试已实现，见 [扩展设计](worker-horizontal-scaling.md) 与 [扩展验证](worker-horizontal-scaling-verification.md)；同一 Attempt 跨 Inbox 接管与更完整故障对账尚未实现，第五步为部分完成。

Worker 节点管理已实现：独立注册/心跳协议与密钥、Scheduler 节点和会话历史、超时离线及乱序/旧会话隔离、MQ 动态节点选择与固定 Attempt 目标检查、Admin 查询和 Vue 工作节点页面。协议及升级见 [节点管理](worker-nodes.md)，验收见 [节点验证](worker-nodes-verification.md)。Worker 支持结果保存重试、恢复上报和耗尽诊断，并有对应故障测试，见 [结果保存恢复](worker-result-recovery.md)。真实 Relay 发布后强杀、Worker 执行中强杀、旧 Inbox 重启后的真实迟到上报、有限重试与发布连接中断恢复已有独立专项，见 [进程故障窗口验证](dispatch-fault-verification.md)。在途 Broker 节点停机、可靠死信与更完整失联恢复仍需独立演练；节点 generation 和执行 leaseVersion 分别维护，不能将节点心跳视为执行续租。

- Admin 配置同步保留 HTTP 事务 Outbox；Scheduler 已有独立派发 Outbox。
- RabbitMQ、发布确认、消费确认和持久化去重已完成真实三节点及单节点停机闭环；旧版死信保障和更完整故障窗口仍待验证。
- 默认任务派发切换到 MQ，已覆盖提交后未发布、发布后未标记和重复事件的故障注入；HTTP 仅显式选择，不能自动双发。
- Worker 持久化、结果重报和有效租约校验配合固定 Worker 有界续租工作；跨节点回收与更完整的故障对账需单独实施，迟到结果继续拒绝。
- 失败分级、有限次数退避重试和业务幂等。

验收：杀 Relay、杀 Worker、重复投递、恢复旧 Worker 均有注入步骤、预期、实际和日志证据。

## 第六步 运维与交付

运行查询边界。Scheduler 读取本服务的权威 Run 和 Attempt 数据。Admin 校验 `run:read` 和查询参数，在本地 SQL 事务外通过版本化 HTTP 代理；Scheduler 在内部入口独立校验。全局列表默认最近 24 小时，显式窗口最长 7 天，每页最多 100 条、最多第 10000 页。省略时间的历史查询须指定精确 Run ID。统计沿用相同时间窗约束，单任务历史沿用按 Job ID 分页的契约。见 [全局运行查询与详情](global-run-query.md)。

运行详情已按 Attempt 提供执行时间线。Admin 在 `run:read` 权限下查询，单次最多读取两个来源各 100 条事件。Worker 暂不可用时，详情继续展示 Scheduler 的派发、租约和权威终态记录。见 [Attempt 执行事件](attempt-events.md)。

全局运行列表已实现：Scheduler 独立查询、Admin run:read 代理、控制台 `/runs` 提供时间/状态/任务 ID/触发来源交集筛选及精确 Run ID 定位。独立运行详情 `/runs/:runId` 从全局列表与单任务运行结果进入，展示配置快照、Attempt 历史、结果码、目标 Worker 和派发起止时间。范围见 [全局运行查询与详情](global-run-query.md)，验证见 [全局列表验证](global-run-query-verification.md) 和 [运行详情验证](run-detail-verification.md)。

工作台的失败总数和失败 Top 5 可跳转到全局运行列表。总数入口保留统计时间窗与失败状态，分组入口再带入任务 ID 与结果码。可打开单条运行详情并带着筛选条件返回。见 [运行统计设计](run-reporting.md)。

平台治理包含：Nginx → Gateway → Admin，内部服务名发现与 Feign 调用、熔断及同步 HTTP 链路追踪；注册/配置中心独立认证、非秘密策略仓库及可选 Jaeger Compose 已提供。默认配置变更重启生效，不提供完整热刷新；Eureka/Config 单实例，HA 和真实 Jaeger 容器验收仍未完成。

- 运行统计（S1）已实现：Scheduler 时间窗汇总、Admin run:read 代理、工作台 24 小时 / 7 天运行概览及失败 Top 5，见 [设计](run-reporting.md) 与 [验证](run-reporting-verification.md)。Worker 状态已有独立节点页面；S2 已实现待发布积压、Attempt 派发逾期/租约到期、心跳年龄、Worker 待上报及执行积压的瞬时采样，见 [指标字典](observability-metrics.md) 与 [验证](observability-metrics-verification.md)。任务延迟当前覆盖逾期数量与年龄，不提供完整端到端耗时分解；告警（S3）、持久化操作审计和结构化日志专项仍未实现。
- Keycloak 用户登录、JWT 验证、管理权限、Redis 30 分钟滑动会话及入口限流已实现，见 [登录与入口安全](access-security.md)；告警、持久化操作审计另行实施。2026-10-28 安全验证时 Redis 不可达，真实 Redis 会话与限流集成验收未执行。
- 各服务镜像和 Compose、一键启动说明、API 示例。
- 在明确拓扑和负载下测量吞吐与延迟，不提前填写性能结论。

验收：从空环境按 README 复现完整流程；每个服务可独立启动、升级和排查。

## 每次开发的完成条件

- 功能归属清楚，不跨服务引用实现或读取其他服务的表。
- 对外协议与失败语义有记录。
- 新增数据库结构有服务自己的版本化迁移。
- 变更涉及的测试通过，未完成的外部环境检查明确标注。
- README 和当前阶段状态与实际代码一致。

## 独立业务接入：订单与库存

销售日报第一版在订单服务 report 包实现，包含店铺/商品成交快照、可重复样例导入、按付款日期统计及日报幂等保存；Worker salesReport 经 HTTP 接入，Admin 提供 run:read 查询代理，控制台可查看明细和下载 CSV。默认日期绑定首次受理时间，晚到数据不覆盖既有日报，完整边界见 [销售日报](sales-reports.md)。

我用独立订单示例检验平台与业务的边界：订单服务保证下单条件扣减、请求幂等、付款与关单互斥、预占释放和流水事务；Worker 通过注册表接入可选订单 Handler，并将有限批量处理结果交给现有 Run/Attempt 重试链路。订单付款期限由订单服务创建时固化，调度只决定检查频率。

当前第一版支持单 SKU 订单、受保护的模拟付款、MySQL 权威库存及 Redis 版本快照。真实支付、独立仓储事件补偿和 Redis 前置预扣保留为后续能力。验收命令和范围见订单示例 README。

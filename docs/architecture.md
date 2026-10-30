# TaskFlow 微服务架构与维护约定

我们按任务配置、调度状态和业务执行划分 Admin、Scheduler、Worker 三个服务，使各自的代码、数据和部署可以独立维护。项目使用单代码仓库和 Maven 聚合构建，各服务独立运行、独立打包，通过版本化协议协作。

Maven 模块负责构建组织；进程隔离、数据归属和协议约束共同构成服务边界。服务之间不共享业务实现、数据表或本地事务。

## 能力与边界

Admin 提供任务管理与详情缓存；Scheduler 接收手动触发，负责 Cron 自动调度和运行状态；Worker 注册 Handler 能力并执行任务。Scheduler 保存计划副本、Run/Attempt、配置快照、派发 Outbox 和 Worker 节点/会话历史；Worker 保存去重 Inbox 与待上报结果，周期上报心跳及负载。

我们默认通过 RabbitMQ 派发任务，按能力和负载选择独立 Worker 队列，并固定每个 Attempt 的目标。执行支持有界续租；租约失效后，在尝试次数上限内新增 Attempt 并重新选择节点。管理台通过 Admin 查询 Scheduler 的权威运行状态和节点状态。三节点 quorum 集群及 Broker 单节点停机的验证结果见 [MQ 验证](mq-verification.md)；旧版 Broker 的死信可靠转移仍有限制。协议与恢复边界见 [水平扩展](worker-horizontal-scaling.md)、[节点管理](worker-nodes.md)、[执行协议](execution-protocol.md) 与 [可靠派发](reliable-dispatch.md)。

公共入口采用独立 Keycloak 身份服务、Gateway/Admin 双重 JWT 验证、Admin 业务权限及 Redis JWT 会话（有效请求滑动续期 30 分钟）。Gateway 仅管理自有 Redis 限流键，不访问 Admin 会话或业务缓存。身份服务独立持久化账号，业务应用不共享其数据表；Scheduler/Worker 使用服务间协议鉴权。见 [登录与入口安全](access-security.md)。

## 浏览器访问与缓存

我们将静态资源交给 Nginx，API 请求经 Gateway 进入 Admin，以集中处理入口路由和安全策略。任务详情查询链路为：**浏览器 → Nginx → Gateway（WebFlux/Netty）→ Admin Tomcat → Redis（命中则返回）→ MySQL（未命中查询并回填缓存）→ 逐层返回**。

Tomcat 使用 Admin 内嵌容器；Redis 由业务代码访问，数据库仍是权威来源。详情查询经独立查询用例先读缓存，未命中查数据库并回填。写用例始终读取数据库，提交后通过事务事件删除缓存；缓存不承担跨服务共享状态。默认 TTL 60 秒，失效失败有限重试后依靠过期淘汰；完整分支、并发旧数据边界与故障行为见 [访问与缓存流程](request-flow.md)。

## 服务划分

| 服务 | 默认端口 | 责任 | 数据归属 |
| --- | --- | --- | --- |
| taskflow-gateway | 8088 | 显式公共路由、请求边界、负载均衡与熔断；不执行业务 | 无业务数据库 |
| taskflow-registry | 8761 | Eureka 注册与服务发现，Basic 认证 | 无业务数据库 |
| taskflow-config-server | 8888 | 非秘密策略配置，native/Git 后端，Basic 认证 | 独立配置仓库 |
| taskflow-admin | 8080 | 管理台、Job 配置、配置对账与受控修复、管理接口；转发运行查询和手动触发请求 | Job 定义、配置版本、配置同步 Outbox、对账和修复记录及限速闸门 |
| taskflow-scheduler | 8081 | 计划、触发、Run/Attempt、派发、重试、租约校验、节点在线状态、MQ 节点选择和固定 Attempt 目标 | 计划副本、配置 Inbox、Run、Attempt、派发 Outbox、节点及会话历史 |
| taskflow-worker | 8082 | Handler 执行、并发控制、注册/心跳及结果上报 | 本地执行 Inbox、待上报结果；不拥有全局 Run 或节点权威状态 |

Admin 不执行任务；Scheduler 不包含业务 Handler；Worker 不直连 Admin 或 Scheduler 的表。运行查询由 Admin 通过 Scheduler API 获取。

Worker 的终态报告由 Scheduler 幂等接收，Scheduler 维护权威尝试历史；Worker 本地持久化用于重复请求处理和结果重发，不形成第二套权威运行状态。当前 startedAt 表示 Scheduler 开始派发的时间，不等同于业务 Handler 精确开始时间。

## 依赖和内部结构

taskflow-web 独立管理 npm 依赖、路由、组件和构建，不加入 Maven 的 Java 服务依赖。Nginx 返回构建产物并代理 /api/ 至 Gateway，再由 lb://taskflow-admin 选择实例；前端不接触数据库和内部服务地址。部署步骤见 [前端与 Nginx](frontend-deployment.md)。Admin 原静态演示页保留，正式控制台使用 Nginx 8090。

根 POM 管理 Java、Boot 版本和模块集合，不向所有子模块注入业务依赖。每个服务按需声明依赖。

服务之间不互相依赖 JAR。当前不建立 common 或 contracts 模块；首次定义远程协议时，先提交协议文档，确有共享需求后才拆只包含协议的模块。协议中不出现数据库实体和仓储实现。

各服务启动类分别位于 com.taskflow.admin、com.taskflow.scheduler、com.taskflow.worker，只扫描自己的包。

新增业务能力按以下方式组织，现阶段不创建空目录或占位实现：

```text
com.taskflow.admin
└─ job
   ├─ controller/          按功能拆分 HTTP Controller
   ├─ service/             IJobService 等业务接口
   │  └─ impl/             JobServiceImpl 等实现类
   ├─ mapper/              MySQL / Redis 数据访问接口
   │  ├─ mysql/            MyBatis-Plus 数据访问实现
   │  └─ redis/            Redis 缓存访问实现
   ├─ dto/                 HTTP 请求与响应
   ├─ model/               业务模型、筛选条件与统计
   ├─ entity/              数据库实体
   ├─ config/              MyBatis / Redis 装配和配置
   ├─ event/               本地业务变更事件
   └─ exception/           业务异常
```

我们先按业务能力分包，再在模块内使用 Controller / Service / Mapper 分层，使相关改动集中在同一业务目录，并限制依赖方向。调用方向为 Controller → Service 接口 → service/impl 实现 → Mapper。所有 Service 接口统一放在业务模块的 service 下，对应实现放在其 impl 子目录，通过 implements 实现；Controller 和其他 Service 依赖接口。没有数据访问需求的业务不创建 Mapper。system 业务包含独立 Controller、Service 接口与实现，以及响应、异常和过滤器。

Admin 使用 MyBatis-Plus 的 Spring Boot 4 Starter。Entity 放在 entity，Mapper 放在 mapper，分页和缓存装配放在 config，业务模型与 API DTO 不添加持久化注解。SQL 接口 JobMapper 标注 @Mapper，扫描只注册该注解对应接口，避免将 JobDataMapper、JobCacheMapper 误注册为 SQL 代理。根 POM 管理 MyBatis-Plus BOM 版本，仅 Admin 声明当前需要的依赖。数据库、Redis 与 MyBatis 参数统一放在 YAML，实际凭据通过忽略的本机 YAML 或环境变量提供，详见 [持久层约定](persistence.md)。

JobController 负责新增、修改、启停和删除；JobQueryController 负责分页、详情和统计；SchedulePreviewController 负责 Cron 预览；HandlerController 负责处理器查询。具体类和验证见 [结构调整记录](structure-verification.md)，路径与 JSON 协议见 [接口文档](api.md)。

## 数据隔离

数据库接入时为三个服务分别管理迁移目录、连接配置和账号。开发环境可共用一个 MySQL 实例，schema 分别为 taskflow_admin、taskflow_scheduler、taskflow_worker；创建时仅给各自 schema 授权。

Admin 使用自己的 V1 / V2 / V3 迁移，其中 V3 引入配置同步 Outbox。Scheduler V2 引入计划副本与同步 Inbox，V3 持久化 next_fire_time、calculated_at 和计算状态，V4 增加计划轮次唯一键，V5 引入派发 Outbox，V6 引入 Worker 节点和会话历史；Scheduler V7 引入执行短租约，V8 引入 Attempt 目标和 Outbox 路由快照及路由唯一约束；Worker V2 持久化结果所属执行身份。三者拥有自己的 MySQL schema、独立账号，各自 Mapper 不引用其他应用模块。运行环境默认使用 RabbitMQ；测试 profile 使用隔离 H2、HTTP 回归及 MQ 边界故障注入，不依赖外部 Broker。MQ 集群历史验收见 [MQ 验证](mq-verification.md)；V8 与集群派发的验证见 [扩展验证](worker-horizontal-scaling-verification.md)。

Admin Job ID 在 Scheduler 中是逻辑引用，不建立跨 schema 外键。Worker 通过协议上报执行状态，不直接更新 Scheduler 的 Run 表。

调度配置由 Admin 管理，Scheduler 保存包含 jobId、configVersion 的计划副本。业务引用的事实需要明确权威来源，配置副本不能成为独立可编辑的第二份 Job。

下一计划时间由 Scheduler 结合配置时区计算，与副本版本在同一事务中保存；查询和重启不重新计算。Admin 通过内部 HTTP 查询实际副本，不从展示缓存推测时间。到期扫描在本地事务中创建 Run/首次 Attempt、MQ 发送意图并推进计划，以数据库唯一键保护同一 Job/计划时间。补算、触发、派发准备和 MQ Relay 使用独立线程，具体见 [计划计算](plan-calculation.md) 与 [自动调度](automatic-scheduling.md)。

## 通信与故障处理

手动触发通过 HTTP 接入：Admin 携带请求幂等键和数据库配置快照调用 Scheduler；Scheduler 在本地事务中保存 Run 与首次 Attempt，再扫描异步派发。Worker 先持久化去重记录并确认接收，执行完成后保存结果，再扫描上报。

手动触发接口返回 HTTP 202 与运行 id，接收成功不代表任务成功。Scheduler 扫描已提交但尚未成功派发的 Attempt，恢复进程退出留下的待执行工作。Worker 重启将之前 RUNNING 的记录标为未知副作用失败，不盲目重做；ACCEPTED 记录可继续执行。

配置同步已采用 Admin 事务 Outbox 推送版本化 HTTP 事件，不依赖分页拉取或全局游标。保存只提交本地事务；后台专用线程领取持久化发送租约，在事务外调用 Scheduler。Scheduler 用 Inbox 和版本检查处理重复、乱序与删除墓碑。失败有限重试，最新失败版本可人工重试。Admin V4 额外持久化对账批次、观察、修复记录及限速闸门，对账不替代正常事件推送。管理台区分保存、副本已同步、节点触发开关及 Run 结果。完整协议、恢复边界和验证见 [异步配置同步](config-sync.md) 与 [配置对账](config-reconciliation.md)。

Scheduler 的 dispatch 模块维护自己的 Outbox、领取版本和有限发布重试，经 correlated confirm 与 mandatory return 判断是否交给 Broker。Worker 的 dispatch 模块校验 v1 事件，复用 execution 模块持久化去重，提交后手动 ACK，永久错误和耗尽接收重试的消息进入死信队列。三节点使用 quorum 队列，应用使用多节点连接地址。Admin 配置同步和 Worker 结果上报仍走 HTTP。Worker 有界续租及 Handler 执行上下文见 [执行租约](execution-leases.md)。Admin 已提供分批配置对账、墓碑检查和带限速/幂等记录的原 Outbox 补发，Scheduler 可在 Inbox 保留时重建缺失副本，见 [配置对账](config-reconciliation.md)。未知孤立副本发现及同一 Attempt 跨 Inbox 接管仍待实现；新增 Attempt 可重新选择 Worker。

节点管理使用独立 HTTP 契约及节点密钥。Scheduler V6 保存 worker_node 和不可复用的 worker_node_session，按接收时间计算有效期，较旧心跳不延长存活，新会话只能替代已过期会话。MQ 派发准备按能力/负载选择节点并绑定目标与路由，发布前仅检查原目标在线及能力/路由匹配；HTTP 保留固定目标。Worker 的独立心跳线程不会占用执行槽位，Admin 代理查询而不建节点副本或缓存。节点 generation 只约束注册和心跳，不代替执行 leaseVersion，不撤销已派发任务，详见 [节点协议](worker-nodes.md)。

不同时维护两套默认派发通道。切换方案时更新协议、迁移步骤和故障验收。

所有内部 API 使用 /internal/v1 前缀，业务管理 API 使用 /api/v1 前缀。实现内部业务接口时同时加入服务身份校验、请求关联信息、超时和有限重试。路径前缀本身不提供访问控制。

## 核心业务语义

- Job 是配置，Run 是一次逻辑运行，Attempt 是一次实际尝试。
- 自动重试新增 Attempt，保留 Run ID；人工再次执行创建新 Run，并关联来源。
- Run 保存配置版本和快照，后续修改 Job 不改变已有 Run。
- 停用 Job 停止后续计划；终止正在执行的任务属于单独能力。
- 最大尝试次数包含首次执行，避免“重试三次”的歧义。
- Run 基础状态：READY、RUNNING、RETRY_WAIT、SUCCEEDED、FAILED。
- 租约过期不意味着旧 Worker 物理停止；结果写入需验证当前租约版本，业务副作用仍需幂等。
- 执行超时、重试次数、重试时间和失败分类显式保存，不靠内存 sleep 实现可靠重试。

## 当前启动配置

| 服务 | 配置变量 | 默认值 |
| --- | --- | --- |
| Admin | TASKFLOW_ADMIN_PORT | 8080 |
| Scheduler | TASKFLOW_SCHEDULER_PORT | 8081 |
| Worker | TASKFLOW_WORKER_PORT | 8082 |

三个服务均暴露 /actuator/health，仅 Admin 提供管理台。Scheduler、Worker 的内部业务接口使用 /internal/v1，与 Admin 通过三份独立密钥协作；默认健康检查不公开连接或密钥。

## 平台治理与部署模式

我们将网关、注册发现和配置中心部署为独立基础设施应用，集中处理入口和服务治理；三个业务服务继续独立拥有数据和事务，平台应用不访问业务数据库。

Java 21 / Boot 4.1.1 与 Spring Cloud 2025.1.4 BOM 由父 POM 管理版本，六个应用分别打包，业务模块之间无 Maven 依赖。cloud 模式使用 Eureka 服务名、OpenFeign/LoadBalancer、Resilience4j、Micrometer OTel/OTLP；默认本地直连模式不依赖注册与配置中心，便于独立开发和隔离测试。具体启动、认证、失败语义及验证边界见 [微服务技术栈](microservice-platform.md)。

## 技术参考

- [Maven 多模块构建](https://maven.apache.org/guides/mini/guide-multiple-modules.html)
- [Spring Boot Maven 插件和依赖管理](https://docs.spring.io/spring-boot/maven-plugin/using.html)
- [Spring Boot Actuator 端点](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
- [XXL-JOB 官方文档](https://github.com/xuxueli/xxl-job/blob/master/doc/XXL-JOB%E5%AE%98%E6%96%B9%E6%96%87%E6%A1%A3.md)

以上用于核对框架能力；服务划分、配置同步和数据所有权是 TaskFlow 自身的设计决定。

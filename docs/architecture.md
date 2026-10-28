# TaskFlow 微服务架构与维护约定

日期：2026-10-27。

## 需求与当前状态

用户要求后续代码按微服务原则拆分模块，职责清楚，便于维护和独立修改。项目采用单代码仓库、Maven 聚合、多个可独立运行和打包的 Spring Boot 服务。

当前已交付三个独立服务、Admin 任务管理与详情缓存、手动执行闭环及 Cron 自动调度。Scheduler 保存计划副本、Run/Attempt、快照、派发 Outbox 和 Worker 节点/会话历史；Worker 保存去重 Inbox 与待上报结果，注册实际 Handler 能力并周期上报心跳及负载。派发默认经 RabbitMQ，管理台通过 Admin 查询 Scheduler 权威状态，新增工作节点页面。三节点 quorum 配置已提供，实际集群验收待执行；执行租约续期和跨节点回收尚未实现。见 [节点管理](worker-nodes.md)、[执行协议](execution-protocol.md) 与 [可靠派发](reliable-dispatch.md)。

Maven 多模块管理构建；独立进程、明确数据归属和协议协作落实服务边界。两个概念需要同时遵守。

## 浏览器访问与缓存目标

用户确认的目标链路为：**浏览器 → Nginx（静态直返 / API 转发）→ Gateway（WebFlux/Netty）→ Admin Tomcat → Redis（命中则返回）→ MySQL（未命中查询并回填缓存）→ 逐层返回**。

Tomcat 使用 Admin 内嵌容器；Redis 由业务代码访问，数据库仍是权威来源。详情查询经独立查询用例先读缓存，未命中查数据库并回填。写用例始终读取数据库，提交后通过事务事件删除缓存；缓存不承担跨服务共享状态。默认 TTL 60 秒，失效失败有限重试后依靠过期淘汰；完整分支、并发旧数据边界与故障行为见 [访问与缓存流程](request-flow.md)。

## 服务划分

| 服务 | 默认端口 | 责任 | 数据归属 |
| --- | --- | --- | --- |
| taskflow-gateway | 8088 | 显式公共路由、请求边界、负载均衡与熔断；不执行业务 | 无业务数据库 |
| taskflow-registry | 8761 | Eureka 注册与服务发现，Basic 认证 | 无业务数据库 |
| taskflow-config-server | 8888 | 非秘密策略配置，native/Git 后端，Basic 认证 | 独立配置仓库 |
| taskflow-admin | 8080 | 管理台、Job 配置、管理接口；转发运行查询和手动触发请求 | Job 定义、配置版本、配置同步 Outbox |
| taskflow-scheduler | 8081 | 计划、触发、Run/Attempt、派发、重试、租约校验、节点在线状态和固定目标检查 | 计划副本、配置 Inbox、Run、Attempt、派发 Outbox、节点及会话历史 |
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

这是用户最新要求的 Controller / Service / Mapper 结构，替代原 interfaces/application/domain/infrastructure 目录。调用方向为 Controller → Service 接口 → service/impl 实现 → Mapper。所有 Service 接口统一放在业务模块的 service 下，对应实现放在其 impl 子目录，通过 implements 实现；Controller 和其他 Service 依赖接口。没有数据访问需求的业务不创建 Mapper。system 业务包含独立 Controller、Service 接口与实现，以及响应、异常和过滤器。

Admin 使用 MyBatis-Plus 的 Spring Boot 4 Starter。Entity 放在 entity，Mapper 放在 mapper，分页和缓存装配放在 config，业务模型与 API DTO 不添加持久化注解。SQL 接口 JobMapper 标注 @Mapper，扫描只注册该注解对应接口，避免将 JobDataMapper、JobCacheMapper 误注册为 SQL 代理。根 POM 管理 MyBatis-Plus BOM 版本，仅 Admin 声明当前需要的依赖。数据库、Redis 与 MyBatis 参数统一放在 YAML，实际凭据通过忽略的本机 YAML 或环境变量提供，详见 [持久层约定](persistence.md)。

JobController 负责新增、修改、启停和删除；JobQueryController 负责分页、详情和统计；SchedulePreviewController 负责 Cron 预览；HandlerController 负责处理器查询。具体类和验证见 [结构调整记录](structure-verification.md)。所有路径与 JSON 协议保持不变。

## 数据隔离

数据库接入时为三个服务分别管理迁移目录、连接配置和账号。开发环境可共用一个 MySQL 实例，schema 分别为 taskflow_admin、taskflow_scheduler、taskflow_worker；创建时仅给各自 schema 授权。

Admin 使用自己的 V1 / V2 / V3 迁移，其中 V3 引入配置同步 Outbox。Scheduler V2 引入计划副本与同步 Inbox，V3 持久化 next_fire_time、calculated_at 和计算状态，V4 增加计划轮次唯一键，V5 引入派发 Outbox，V6 引入 Worker 节点和会话历史；Worker 保持 V1 执行迁移。三者拥有自己的 MySQL schema、独立账号，各自 Mapper 不引用其他应用模块。运行环境默认使用 RabbitMQ；测试 profile 使用隔离 H2、HTTP 回归及 MQ 边界故障注入，不依赖外部 Broker。V5/V6 的真实 MySQL 升级及 MQ 集群验收尚待执行。

Admin Job ID 在 Scheduler 中是逻辑引用，不建立跨 schema 外键。Worker 通过协议上报执行状态，不直接更新 Scheduler 的 Run 表。

调度配置由 Admin 管理，Scheduler 保存包含 jobId、configVersion 的计划副本。业务引用的事实需要明确权威来源，配置副本不能成为独立可编辑的第二份 Job。

下一计划时间由 Scheduler 结合配置时区计算，与副本版本在同一事务中保存；查询和重启不重新计算。Admin 通过内部 HTTP 查询实际副本，不从展示缓存推测时间。到期扫描在本地事务中创建 Run/首次 Attempt、MQ 发送意图并推进计划，以数据库唯一键保护同一 Job/计划时间。补算、触发、派发准备和 MQ Relay 使用独立线程，具体见 [计划计算](plan-calculation.md) 与 [自动调度](automatic-scheduling.md)。

## 分阶段通信方案

第一条业务链路已使用 HTTP 实现：Admin 接受手动触发，携带请求幂等键和数据库配置快照调用 Scheduler；Scheduler 在本地事务中保存 Run 与首次 Attempt，再扫描异步派发。Worker 先持久化去重记录并确认接收，执行完成后保存结果，再扫描上报。

手动触发接口返回 HTTP 202 与运行 id，接收成功不代表任务成功。Scheduler 扫描已提交但尚未成功派发的 Attempt，恢复进程退出留下的待执行工作。Worker 重启将之前 RUNNING 的记录标为未知副作用失败，不盲目重做；ACCEPTED 记录可继续执行。

配置同步已采用 Admin 事务 Outbox 推送版本化 HTTP 事件，不依赖分页拉取或全局游标。保存只提交本地事务；后台专用线程领取持久化发送租约，在事务外调用 Scheduler。Scheduler 用 Inbox 和版本检查处理重复、乱序与删除墓碑。失败有限重试，最新失败版本可人工重试。管理台区分保存、副本已同步、节点触发开关及 Run 结果。完整协议、恢复边界和验证见 [异步配置同步](config-sync.md)。

Scheduler 的 dispatch 模块维护自己的 Outbox、领取版本和有限发布重试，经 correlated confirm 与 mandatory return 判断是否交给 Broker。Worker 的 dispatch 模块校验 v1 事件，复用 execution 模块持久化去重，提交后手动 ACK，永久错误和耗尽接收重试的消息进入死信队列。三节点使用 quorum 队列，应用使用多节点连接地址。Admin 配置同步和 Worker 结果上报仍走 HTTP。固定 Worker 有界续租及 Handler 执行上下文见 [执行租约](execution-leases.md)。全量对账、副本重建及跨 Worker 接管待实现。

节点管理使用独立 HTTP 契约及节点密钥。Scheduler V6 保存 worker_node 和不可复用的 worker_node_session，按接收时间计算有效期，较旧心跳不延长存活，新会话只能替代已过期会话。派发准备和 MQ 发布前检查固定目标在线及能力/路由匹配。Worker 的独立心跳线程不会占用执行槽位，Admin 代理查询而不建节点副本或缓存。节点 generation 只约束注册和心跳，不代替执行 leaseVersion，不撤销已派发任务，详见 [节点协议](worker-nodes.md)。

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

## 对已有计划的调整

保留原 Word 计划的 Job/Run/Attempt、Outbox、有限重试、租约和故障验证目标。单应用、APP_ROLE 和各角色共用权威状态库的实现路径改为三个独立服务；不跨服务共用数据库事务。

按用户最新要求增加网关、注册发现、配置中心和服务治理。保留 Java 21 / Boot 4.1.1，父 POM 引入兼容的 Spring Cloud 2025.1.4 BOM；新增三个基础设施应用，业务模块仍无相互 Maven 依赖。cloud 模式使用 Eureka 服务名、OpenFeign/LoadBalancer、Resilience4j、Micrometer OTel/OTLP；默认本地直连模式不依赖注册与配置中心。具体启动、认证、失败语义及验证边界见 [微服务技术栈](microservice-platform.md)。

## 技术参考

- [Maven 多模块构建](https://maven.apache.org/guides/mini/guide-multiple-modules.html)
- [Spring Boot Maven 插件和依赖管理](https://docs.spring.io/spring-boot/maven-plugin/using.html)
- [Spring Boot Actuator 端点](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
- [XXL-JOB 官方文档](https://github.com/xuxueli/xxl-job/blob/master/doc/XXL-JOB%E5%AE%98%E6%96%B9%E6%96%87%E6%A1%A3.md)

以上用于核对框架能力；服务划分、配置同步和数据所有权是 TaskFlow 自身的设计决定。

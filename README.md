# TaskFlow

TaskFlow 是参考 XXL-JOB 架构的 Java 后端学习项目，目标是构建职责清晰、可独立运行的分布式任务调度与可靠执行服务。

## 当前进度

已完成三个独立服务骨架，以及 Admin 的 MyBatis-Plus / MySQL / Flyway 接入和任务管理接口：新增、分页查询、详情、修改、启停、软删除、统计、Cron 预览与配置处理器查询。

已补齐微服务技术栈：Gateway WebFlux/Netty、Eureka、Config Server、OpenFeign/LoadBalancer、Resilience4j、Micrometer Tracing/OTLP。新增三个独立基础设施应用；公共 API 统一经 Gateway，cloud 模式通过服务名调用。Java 21 / Boot 4.1.1 保持不变，使用 Cloud 2025.1.4 BOM。启动与边界见 [微服务技术栈](docs/microservice-platform.md)。

独立 Vue 3 管理台已连接真实 Admin 接口，支持任务管理、Cron 预览、手动执行与运行历史，由 Nginx 托管。Admin 已实现 Redis 详情缓存，默认 TTL 60 秒。手动执行已打通 Admin → Scheduler → Worker → 结果上报：Run/Attempt、执行快照、幂等接收、有限重试、超时与重启恢复均有独立持久化。配置异步同步已实现：Admin 事务 Outbox、后台有限重试、Scheduler 版本副本和删除墓碑。Cron 已支持到期扫描、计划轮次去重及 SKIP/FIRE_ONCE。Scheduler 派发 Outbox、RabbitMQ 发布确认、Worker Inbox 提交后消费确认已实现；远程三节点正常与单节点停机场景各 43 项真实 MQ 检查通过，当前 3.8 Broker 使用显式兼容策略，至少一次死信转移仍未验收。Worker 注册、心跳、在线/离线判定及节点查询页面已实现，固定目标派发会检查节点有效期、Handler 和路由；执行租约续期与跨节点回收尚未实现。Java 21 与 Spring Boot 4.1.1 保持不变。

Worker 结果保存恢复已补齐：同一份结果有限重试写库，恢复后继续上报，耗尽有可定位错误，保存重试不重新调用 Handler；线程池拒绝已领取任务也有收尾。进程崩溃、重试耗尽和过期租约仍有明确限制，见 [结果保存恢复](docs/worker-result-recovery.md)。

## 模块和边界

| 模块 | 默认端口 | 职责 |
| --- | --- | --- |
| taskflow-gateway | 8088 | 公共 API 入口、负载均衡、请求限制与熔断 |
| taskflow-registry | 8761 | Eureka 注册发现，独立认证 |
| taskflow-config-server | 8888 | 非秘密集中配置，native/Git 后端 |
| taskflow-admin | 8080 | 任务定义、管理 API、控制台 |
| taskflow-scheduler | 8081 | 调度计划、Run/Attempt 状态、派发与恢复 |
| taskflow-worker | 8082 | Handler 执行、消费去重、节点注册/心跳与结果上报 |
| taskflow-web / Nginx | 8090（本机） | 静态控制台与 /api/ 反向代理；独立前端构建 |

根 pom.xml 只聚合和管理构建，各服务分别打包为可执行 JAR。服务之间通过 HTTP API 或版本化消息事件协作，不直接引用业务实现，各自拥有数据和迁移。

## 文档入口

- [长期代码与协作约定](AGENTS.md)
- [Git 版本管理、回退与备份](docs/version-control.md)
- [微服务架构和数据归属](docs/architecture.md)
- [微服务技术栈、网关与配置](docs/microservice-platform.md)
- [平台和链路验证记录](docs/microservice-platform-verification.md)
- [Controller / Service / Mapper 目录与验证](docs/structure-verification.md)
- [浏览器、Nginx、Tomcat、Redis 与数据库访问流程](docs/request-flow.md)
- [Admin Redis 缓存配置与验证](docs/redis.md)
- [任务详情缓存验证记录](docs/cache-verification.md)
- [手动执行 HTTP 协议](docs/execution-protocol.md)
- [配置异步同步、状态与恢复](docs/config-sync.md)
- [Scheduler 下一计划时间计算与查询](docs/plan-calculation.md)
- [Cron 自动调度](docs/automatic-scheduling.md)
- [可靠派发、RabbitMQ 三节点集群与切换](docs/reliable-dispatch.md)
- [Cron 复测与 MQ 验证边界](docs/mq-verification.md)
- [Worker 注册、心跳、派发检查与节点页面](docs/worker-nodes.md)
- [节点模块验证记录](docs/worker-nodes-verification.md)
- [手动执行验证与本机运行](docs/execution-verification.md)
- [分阶段开发与验收计划](docs/development-plan.md)
- [Admin 任务表与手动建表步骤](docs/database.md)
- [MyBatis-Plus 持久层与配置约定](docs/persistence.md)
- [业务功能与接口文档](docs/api.md)
- [可直接调用的 HTTP 请求示例](docs/taskflow-admin.http)
- [后端接口验证记录](docs/backend-verification.md)
- [前端构建与 Nginx 部署](docs/frontend-deployment.md)
- [前端与 Nginx 验证记录](docs/frontend-verification.md)
- [第一步验证记录](docs/verification.md)

原 Word 开发计划保留为参考，其中单应用、APP_ROLE 和共享状态库的方案以最新 Markdown 约定为准。

## 前端控制台

新发行包前端入口为 http://localhost:8090，Nginx 将 /api/ 转发至 Gateway 8088，再发现 Admin。已有运行实例不会因修改模板自动切换；先启动平台，再按 [前端部署文档](docs/frontend-deployment.md) 更新发行包。

```powershell
# 第一次或修改前端后：构建与打包
.\tools\package-frontend.ps1
# 初次激活；已有实例更新时需先用 stop 停止旧实例
Copy-Item -LiteralPath dist/latest-build.txt -Destination dist/current-release.txt
.\tools\nginx.ps1 -Action start
```

打包输出 dist/taskflow-web.zip 与含 Nginx 的 dist/taskflow-nginx-windows.zip。Gateway 与后端服务需另行运行。访问链路及 Redis 缓存一致性边界见 [访问流程](docs/request-flow.md)。

## 构建和测试

安装 JDK 21，配置 JAVA_HOME 并确保 java 可用。在项目根目录执行：

```powershell
.\mvnw.cmd verify
```

Maven Wrapper 首次运行需要网络下载 Maven 与依赖。默认业务测试使用独立 H2，不连接用户数据库；MySQL 迁移与 JSON 行为另有真实 MySQL 验证。后端测试不需要 RabbitMQ 或 Node.js。前端构建和浏览器测试需要 Node.js；根目录 `.\tools\test-frontend.ps1` 使用隔离 Gateway 和三个业务进程，不写用户 MySQL。完整真实注册/配置/OTLP 验收执行 `.\tools\test-platform.ps1 -SkipBuild`。

Scheduler 的 Run/Attempt 状态竞争另有真实 MySQL 验收：本机安装 MySQL 工具并加入 PATH 后执行 `.\tools\test-run-state-mysql.ps1`。脚本创建全新临时实例和随机测试 schema，默认端口 13387，结束后停止该实例，不读取业务连接配置；默认 `verify` 仍使用隔离 H2。修复内容与验证范围见 [状态并发修复](docs/run-state-concurrency-verification.md)。

加入 `-IncludeDispatchFairness` 可同时验收超过 100 条不可派发任务时的扫描公平性、MQ 发布等待及租约保护。无合格节点时默认延后 2 秒检查，配置为 `taskflow.dispatch.node-recheck-delay` / `TASKFLOW_NODE_RECHECK_DELAY`。实现与测试边界见 [扫描公平性验证](docs/dispatch-fairness-verification.md)。

## RabbitMQ 集群

Scheduler / Worker 的运行配置默认使用 RabbitMQ；Admin 配置同步和 Worker 结果上报继续使用 HTTP。本地集群需要 Docker Compose v2：

```powershell
.\tools\mq-cluster.ps1 -Action init
.\tools\mq-cluster.ps1 -Action up
.\tools\mq-cluster.ps1 -Action status
# 使用随机隔离测试 vhost，含单节点停机后重跑
.\tools\test-mq.ps1 -SkipBuild -SkipBrowser
```

init 可在没有 Docker 时生成被忽略的随机凭据、Broker 定义和服务 YAML，不启动服务。up 创建三节点、独立数据卷并检查 quorum 队列成员。宿主机 AMQP 端口为 5672/5673/5674，管理端口为 15672/15673/15674，只绑定回环地址。同一台主机的三个容器只能验证节点故障，生产应分布到不同主机。

已有外部集群通过 `tools/connect-mq.ps1 -ClusterSettingsPath .tools/mq-cluster-settings.json` 接入；它读取本机 init 已生成的分角色定义、创建三副本资源，并保存被忽略的 `config/taskflow-mq-cluster-local.yml`。Scheduler/Worker 已导入该共享连接配置，账号仍各自独立，使用新 JAR 或 IDE 重启后生效。3.8/3.9 需要显式添加 `-AllowLegacyBroker`，旧版不提供至少一次死信转移保障。外部集群设置模板、人工停机检查点和完整命令见 [已有集群接入与验收](docs/reliable-dispatch.md#接入已有外部集群)，实测结果见 [MQ 验证记录](docs/mq-verification.md)。

集群尚未搭建时，显式设置 `$env:TASKFLOW_DISPATCH_TRANSPORT='http'` 与 `$env:TASKFLOW_MQ_HEALTH_ENABLED='false'`，可保留原 HTTP 运行方式。测试 profile 自动关闭 Rabbit 健康探测；切换前必须排空在途工作，操作与协议见 [可靠派发](docs/reliable-dispatch.md)。

## Worker 节点管理

管理台新增 `/workers`，支持在线/离线统计、名称或标识筛选、分页、能力和负载展示。Worker 默认每 5 秒上报一次，Scheduler 在最后有效心跳 30 秒后判为离线，派发前检查固定目标 `worker-default` 的状态。节点心跳与执行租约是不同机制，节点恢复不会替旧 Attempt 续租。

升级前运行 `.\tools\prepare-node-identity.ps1` 生成被忽略的 `config/taskflow-nodes-local.yml`；已有文件会保留，也可在 Scheduler/Worker 进程中设置相同 `TASKFLOW_NODE_TOKEN`。先升级 Scheduler（Flyway V6），再升级 Worker、Admin 和前端。Worker 注册成功前新任务保留 READY，重启新会话需等待旧会话超时。内部查询仍使用原控制密钥，节点身份密钥不进入浏览器。协议与限制见 [节点文档](docs/worker-nodes.md)。

## Admin 数据库配置

运行 Admin 需要 MySQL，默认连接 localhost:3306/taskflow_admin，用户名 root。数据库和 Redis 的实际本机连接配置统一保存在 config/taskflow-admin-local.yml，由主配置 application.yml 引入；该文件已被 .gitignore 排除，不会打包进 JAR。TASKFLOW_ADMIN_DB_URL、TASKFLOW_ADMIN_DB_USERNAME、TASKFLOW_ADMIN_DB_PASSWORD 可覆盖本机配置。

本机已完成连接和 V2 迁移，不需要再次手工建表或开启基线。本机忽略配置已将 Admin 设为 18080；TASKFLOW_ADMIN_PORT 仍可覆盖端口。当前可访问 http://localhost:18080/api/v1/jobs/select，其他接口也使用 18080。

从项目根目录执行以下命令即可读取已保存的本机配置并启动；IDE 启动时将工作目录设为项目根目录：

```powershell
.\mvnw.cmd -pl taskflow-admin spring-boot:run
```

其他开发环境没有本机配置时，可在终端设置环境变量，例如将下方示意值替换为实际值，然后在同一个终端启动 Admin：

```powershell
$env:TASKFLOW_ADMIN_DB_PASSWORD = '替换为你本机的数据库密码'
.\mvnw.cmd -pl taskflow-admin spring-boot:run
```

已确认本机 MySQL 8.0.34、3306、taskflow_admin，原 V1 结构核对一致后已建立基线并迁移到 V2；任务表现有 17 个字段，当前 0 条记录。注意数据库端口 3306 与当前 Admin HTTP 端口 18080 不同。

数据库需先存在。空库启动时 Flyway 自动执行 V1、V2 和 V3；已有 V2 库自动执行 V3，新建配置 Outbox 并回填现有任务及墓碑。Scheduler 执行自己的 V2 计划副本迁移及 V3 下一计划时间迁移，旧副本由后台分批补算，建议先升级 Scheduler。详见 [配置同步](docs/config-sync.md)、[计划计算](docs/plan-calculation.md)。

如果已经手工执行过之前的 V1 建表 SQL，先按 [数据库文档](docs/database.md) 核对结构，再仅在首次接管时设置 TASKFLOW_ADMIN_DB_BASELINE_ON_MIGRATE=true，由 Flyway 建立版本 1 基线并执行 V2。默认关闭自动基线；不要重复执行 V1，也不要在不匹配的库上开启此选项。后续启动移除该变量。

启用基线的首次启动示例，密码需已在本机设置：

```powershell
$env:TASKFLOW_ADMIN_DB_BASELINE_ON_MIGRATE = 'true'
.\mvnw.cmd -pl taskflow-admin spring-boot:run
# 停止服务后移除，只需设置这一次
Remove-Item Env:TASKFLOW_ADMIN_DB_BASELINE_ON_MIGRATE
```

Scheduler 和 Worker 现在分别需要自己的 MySQL schema 和账号，迁移与业务数据互相隔离。本机准备脚本只使用已保存的 Admin 管理连接创建独立空 schema、独立账号及本机密钥，不操作 Admin 业务表：

```powershell
.\tools\prepare-local-execution.ps1
.\tools\prepare-platform.ps1
.\tools\prepare-node-identity.ps1
.\tools\services.ps1 -Action start
```

实际连接分别写入被忽略的 config/taskflow-scheduler-local.yml、config/taskflow-worker-local.yml，三份内部服务密钥放在 config/taskflow-internal-local.yml。已有本机配置不会被自动覆盖。配置与失败语义见 [执行协议](docs/execution-protocol.md)。

## 本地运行

Admin 主配置位于 taskflow-admin/src/main/resources/application.yml，统一引入被 Git 忽略的 config/taskflow-admin-local.yml，其中 spring.datasource 与 spring.data.redis 保存数据库和 Redis 本机参数。Redis 实际使用仅密码认证，用户名留空；本机开启 Redis 健康检查。配置变量、验证命令与缓存实现边界见 [Redis 文档](docs/redis.md)。普通自动化测试不依赖远程 Redis。

推荐先执行 `prepare-platform.ps1`，再使用 `services.ps1 -Action start` 按顺序启动六个应用，其中 Gateway 和业务服务启用 cloud。下方直接 Maven/JAR 启动保留独立开发模式，默认不启用注册发现/配置中心；需要 cloud 时添加 profile，见 [平台文档](docs/microservice-platform.md)。

独立开发可在三个终端分别执行：

```powershell
.\mvnw.cmd -pl taskflow-admin spring-boot:run
```

```powershell
.\mvnw.cmd -pl taskflow-scheduler spring-boot:run
```

```powershell
.\mvnw.cmd -pl taskflow-worker spring-boot:run
```

根项目已改为聚合 POM，启动时需要使用 -pl 选择服务。在 IDE 中也可以分别运行三个 Application 启动类。

本机也可使用以下后台管理脚本，它将构建 JAR 复制到 .tools/runtime 再启动，避免正在运行的服务锁住 target 构建产物。脚本只管理自己记录且身份匹配的进程：

```powershell
.\tools\services.ps1 -Action status
.\tools\services.ps1 -Action restart -Service admin
# 全部停止：.\tools\services.ps1 -Action stop
```

构建后还可以在三个终端分别运行独立 JAR：

```powershell
java -jar taskflow-admin/target/taskflow-admin-0.0.1-SNAPSHOT.jar
```

```powershell
java -jar taskflow-scheduler/target/taskflow-scheduler-0.0.1-SNAPSHOT.jar
```

```powershell
java -jar taskflow-worker/target/taskflow-worker-0.0.1-SNAPSHOT.jar
```

访问地址：

- 本机正式控制台：http://localhost:8090/（独立前端，由 Nginx 托管）
- Admin 原演示页：http://localhost:18080/（早期演示，正式使用 8090）
- 本机基础接口：http://localhost:18080/api/v1/hello
- 本机 Admin 健康检查：http://localhost:18080/actuator/health
- 本机任务查询：http://localhost:18080/api/v1/jobs/select
- Scheduler 健康检查：http://localhost:8081/actuator/health
- Worker 健康检查：http://localhost:8082/actuator/health

通过 TASKFLOW_ADMIN_PORT、TASKFLOW_SCHEDULER_PORT、TASKFLOW_WORKER_PORT 分别覆盖默认端口，例如：

```powershell
$env:TASKFLOW_ADMIN_PORT = '9080'
.\mvnw.cmd -pl taskflow-admin spring-boot:run
```

## 项目结构

```text
TaskFlow/
├─ AGENTS.md
├─ pom.xml
├─ docs/
├─ taskflow-web/             独立 Vue 控制台
├─ deploy/nginx/             Nginx 配置模板与管理脚本
├─ tools/                    打包、启动、隔离浏览器验收
├─ taskflow-gateway/         WebFlux 公共入口，无业务数据库
├─ taskflow-registry/        Eureka Server
├─ taskflow-config-server/   Config Server
├─ deploy/config-repo/       非秘密集中策略
├─ deploy/platform/          基础设施容器配置
├─ taskflow-admin/
│  └─ src/main/
│     ├─ java/com/taskflow/admin/TaskFlowAdminApplication.java
│     ├─ java/com/taskflow/admin/job/        controller、service/impl、mapper 等
│     ├─ java/com/taskflow/admin/system/     controller、service/impl、DTO 与错误处理
│     └─ resources/static/index.html
├─ taskflow-scheduler/
│  └─ src/main/java/com/taskflow/scheduler/TaskFlowSchedulerApplication.java
└─ taskflow-worker/
   └─ src/main/java/com/taskflow/worker/TaskFlowWorkerApplication.java
```

## 下一步

任务管理、Cron 自动执行、可靠派发代码、Worker 节点管理和微服务治理基础已实现。Worker 结果保存故障已按重试、恢复上报、耗尽诊断和故障测试补齐。真实 MQ 正常与单节点停机闭环已通过；后续补充可靠死信、在途消息故障与多数派丢失验收，并推进执行租约续期与失联恢复；Jaeger 容器实测及全量配置对账仍需补齐。平台能力与验证范围见 [微服务技术栈](docs/microservice-platform.md)。

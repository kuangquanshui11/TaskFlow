# taskflow-admin

管理台与任务定义服务。拥有 Job 配置，运行查询和触发通过 Scheduler 协议协作；不执行调度扫描或业务 Handler。

## 当前实现

独立 Spring Boot 应用、MyBatis-Plus/MySQL、Flyway V1/V2 迁移、任务新增与分页查询、详情、修改、启停、软删除、配置统计、Cron 预览和处理器白名单查询。按业务模块组织 controller、service、service/impl 和 mapper，包含 HTTP 集成测试。持久层结构与配置见 [持久层文档](../docs/persistence.md)。

独立 Vue 控制台通过 Nginx 接入真实接口，Admin 原首页保留为早期演示页。手动执行通过 run/controller → IRunService → run/service/impl → SchedulerClient 提交和查询，数据库权威配置在远程调用前读取完毕。Admin 不执行调度或 Handler，执行协议见 [手动执行](../docs/execution-protocol.md)。

node 模块提供 `/api/v1/workers/select` 和 `/selectById`，通过独立 Service/HTTP Client 查询 Scheduler 节点权威状态，支持分页、筛选和协议校验。Admin 不保存节点表、不使用 Job 详情缓存，Scheduler 不可用返回 503；Vue 管理台新增工作节点页，见 [节点协议](../docs/worker-nodes.md)。

任务详情采用 Redis cache-aside 缓存，默认启用、TTL 60 秒；写入提交后删除详情缓存，最多尝试三次，失败后由 TTL 淘汰。缓存读写故障回退数据库；分页和统计直接查库。配置和一致性限制见 [Redis 缓存文档](../docs/redis.md)。修改、启停和删除始终读取数据库并校验版本。

## 本地启动

在仓库根目录执行：

```powershell
.\mvnw.cmd -pl taskflow-admin spring-boot:run
```

默认数据库地址 localhost:3306/taskflow_admin，账号 root；可用 TASKFLOW_ADMIN_DB_URL、TASKFLOW_ADMIN_DB_USERNAME 覆盖。本机参数保存在被 Git 忽略的 config/taskflow-admin-local.yml，密码可用 TASKFLOW_ADMIN_DB_PASSWORD 环境变量覆盖。本机已迁移到 V2，直接执行启动命令即可，不需要再次设置密码或基线；其他开发环境需自行提供环境变量或本机忽略的 YAML 配置。

数据库需先存在，空库由 Flyway 自动迁移。其他既有手工 V1 表需要先阅读 [数据库接管步骤](../docs/database.md)，核对结构后再显式建立基线。Maven 插件将工作目录固定为聚合工程根目录，IDE 也应使用该工作目录。

服务默认端口 8080；本机忽略配置使用 18080，可用 TASKFLOW_ADMIN_PORT 环境变量覆盖。本机健康检查：http://localhost:18080/actuator/health。

完整业务路径与请求体见 [接口文档](../docs/api.md)，可调用示例见 [taskflow-admin.http](../docs/taskflow-admin.http)。

## 开发规则

功能按业务能力组织，Controller 按职责拆类并调用 Service 接口。service 统一存放 IxxxService 接口，service/impl 存放 implements 对应接口的 XxxServiceImpl；mapper 按需访问本服务数据库或 Redis。DTO、业务模型、持久化实体与框架配置分别放 dto、model、entity、config。不得添加另外两个服务的 Maven 依赖，不共享实体或数据访问实现。

现有任务 Controller 分别是 JobController（配置写入）、JobQueryController（分页、详情、统计）、SchedulePreviewController（Cron 预览）和 HandlerController（处理器白名单）。完整目录和验证见 [结构调整记录](../docs/structure-verification.md)。

完整边界见 [架构文档](../docs/architecture.md)，实施顺序见 [开发计划](../docs/development-plan.md)。

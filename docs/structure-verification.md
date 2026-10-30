# Controller / Service / Mapper 结构调整

日期：2026-10-26。我们按业务能力组织目录，在模块内使用 Controller / Service / Mapper 分层，使业务改动集中、调用方向清晰。三个服务的数据归属、依赖隔离和功能范围不受目录调整影响，组织约束见根目录 AGENTS.md。

本文保留目录调整当时的范围；之后 Admin 新增 run 业务，Scheduler / Worker 新增 execution 业务，均沿用本文的 Controller / Service / impl / Mapper 约定。最新功能与验收见 [执行验证](execution-verification.md)。

## 当前目录

任务模块位于 taskflow-admin/src/main/java/com/taskflow/admin/job：

```text
job/
├─ controller/
│  ├─ JobController.java
│  ├─ JobQueryController.java
│  ├─ SchedulePreviewController.java
│  └─ HandlerController.java
├─ service/
│  ├─ IJobService.java
│  ├─ IJobDetailService.java
│  ├─ ISchedulePreviewService.java
│  ├─ IHandlerService.java
│  └─ impl/
│     ├─ JobServiceImpl.java
│     ├─ JobDetailServiceImpl.java
│     ├─ SchedulePreviewServiceImpl.java
│     └─ HandlerServiceImpl.java
├─ mapper/
│  ├─ JobMapper.java
│  ├─ JobDataMapper.java
│  ├─ JobCacheMapper.java
│  ├─ mysql/MybatisJobDataMapper.java
│  └─ redis/RedisJobCacheMapper.java
├─ dto/          请求与响应
├─ model/        任务配置、筛选条件和统计
├─ entity/       数据库实体
├─ config/       MyBatis 插件、缓存参数与 Bean 装配
├─ event/        事务提交后使用的任务变更事件
└─ exception/    业务异常
```

system 模块位于同级目录，包含 controller/HelloController、service/ISystemService、service/impl/SystemServiceImpl，以及 dto、exception、filter。它不访问数据库或 Redis，因此没有创建空 Mapper。Scheduler 和 Worker 当前只有启动骨架，未来业务也遵循同一目录约定，不预建空业务类。

## 职责与调用

| Controller | 职责 | Service 接口与实现 |
| --- | --- | --- |
| JobController | 创建、修改、启停、删除配置 | IJobService → JobServiceImpl |
| JobQueryController | 分页、详情、配置统计 | IJobService → JobServiceImpl；IJobDetailService → JobDetailServiceImpl |
| SchedulePreviewController | Cron 时间预览 | ISchedulePreviewService → SchedulePreviewServiceImpl |
| HandlerController | 处理器白名单查询 | IHandlerService → HandlerServiceImpl |
| HelloController | 原有基础接口 | ISystemService → SystemServiceImpl |

所有 Service 接口放在各业务模块的 service 目录；全部实现类在其 impl 子目录，通过 implements 实现对应接口。Controller 和其他 Service 注入接口，不直接注入 Mapper。请求 DTO 与响应转换保留在 HTTP 边界，业务校验与事务继续位于 Service 实现。

MySQL 数据访问经 JobDataMapper → MybatisJobDataMapper → JobMapper 完成；Redis 数据访问经 JobCacheMapper → RedisJobCacheMapper 完成。SQL Mapper 标注 @Mapper，MyBatis 扫描器只注册带此注解的接口，避免把普通数据访问接口或 Redis 接口创建为 SQL 代理。

JobFilter、JobSummary 从旧仓储接口中独立为 model 类，Service 接口和 Controller 无需依赖 Mapper 的内部类型。配置实体和数据库实体继续分离。HTTP 路径、参数、JSON 字段、配置版本校验、软删除、TTL、提交后缓存失效和故障降级保持兼容；新目录不改变业务协议。

## 验证

根目录实际命令：

```powershell
.\mvnw.cmd -o -B '-Daether.syncContext.named.factory=rwlock-local' -l .tools/verification/controller-service-mapper-verify.log clean verify
```

结果：BUILD SUCCESS，44 项通过，4 项远程 Redis 检查按默认配置跳过，无失败或错误。clean 清除了旧包的编译残留，三个服务各自重新生成可执行 JAR。

| 验证组 | 结果 |
| --- | --- |
| Admin 原有接口、数据库故障和基础接口 | 20 项通过，Controller 拆分后路径和响应仍兼容 |
| 缓存事务与 HTTP 集成测试 | 10 项通过，验证 Service 接口代理、提交后失效、回滚、版本冲突和并发回填 |
| RedisJobCacheMapperTests | 10 项通过，验证 Redis Mapper 的序列化、TTL、降级和有限删除重试 |
| Scheduler / Worker 启动与资源隔离 | 各 2 项通过 |

另外显式开启 TASKFLOW_REDIS_CACHE_TEST 和 TASKFLOW_REDIS_CONNECTION_TEST，对重构后的代码运行 JobDetailRedisIntegrationTests、RedisConnectionIntegrationTests。结果为 BUILD SUCCESS，4 项全部通过，覆盖真实认证、读写命中、TTL 自动过期、修改/启停/删除后的失效和损坏缓存恢复；日志位于 .tools/verification/controller-service-mapper-redis.log。SQL 仅用独立 H2，Redis 仅访问测试随机前缀下的精确 key，并在结束后清理。

打包检查确认 Admin JAR 包含任务模块 4 个 Controller、job/system 合计 5 个 Service 接口与 5 个实现类；没有旧 interfaces、application、domain、infrastructure 包残留，没有打入本机凭据配置或测试 YAML。源码中迁移后留下的空目录已清理，Controller 没有数据库或 Redis Mapper 依赖。

浏览器测试与真实 MySQL 可写验证未重新执行，业务服务未停止或重启。部署时重新启动 Admin 即可加载新代码，前端和 Nginx 无需重新构建。

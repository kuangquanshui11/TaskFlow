# Admin Redis 任务详情缓存

## 当前接入范围

Admin 已增加 Spring Data Redis / Lettuce 依赖，连接参数统一声明在 taskflow-admin/src/main/resources/application.yml 的 spring.data.redis 下。原 Admin application.properties 已迁移为 YAML，避免同目录两种主配置互相覆盖。Redis 仅归属 Admin 的数据适配，不改变 Scheduler、Worker 的边界。

已交付连接、认证、健康检查和任务详情缓存。GET /api/v1/jobs/selectById 默认使用 cache-aside：先读 Redis，未命中查 MySQL，数据库只读事务结束后回填。分页与统计不缓存；配置修改、启停、删除和写操作返回值始终读取数据库。Service 接口统一位于 job/service，实现位于 job/service/impl；JobCacheMapper 位于 job/mapper，Redis 实现位于 job/mapper/redis，装配与参数类位于 job/config，详见 [访问流程](request-flow.md)。

## 本机认证方式

用户提供的 Redis 地址可达。指定 shenyufei 用户认证返回 WRONGPASS；使用同一个密码、仅密码认证成功，PING 返回 PONG。因此本机配置使用空用户名，即默认用户认证。用户随后提供的终端截图确认 shenyufei 是 Ubuntu 登录账户；不据此推断 Redis 已配置同名 ACL 用户。

没有修改 Redis 服务端配置、用户权限，也没有清空或扫描已有业务数据。

## 配置来源

数据库和 Redis 的实际本机参数统一保存在 config/taskflow-admin-local.yml，分别使用 spring.datasource 和 spring.data.redis；主 application.yml 通过 spring.config.import 引入。该本机 YAML 已加入 .gitignore，启动从仓库根目录读取，不打入 JAR 或前端包。旧的两个本机 properties 文件已移除。不要复制本机 YAML 内容到公共文档。

其他环境通过以下变量覆盖：

| 环境变量 | 用途 | 无本机配置时默认值 |
| --- | --- | --- |
| TASKFLOW_ADMIN_REDIS_HOST | Redis 地址 | localhost |
| TASKFLOW_ADMIN_REDIS_PORT | Redis 端口 | 6379 |
| TASKFLOW_ADMIN_REDIS_USERNAME | ACL 用户名；仅密码认证时留空 | 空 |
| TASKFLOW_ADMIN_REDIS_PASSWORD | Redis 密码 | 空 |
| TASKFLOW_ADMIN_REDIS_DATABASE | 逻辑数据库编号 | 0 |
| TASKFLOW_ADMIN_REDIS_ENABLED | 是否纳入 Admin 健康检查 | false |
| TASKFLOW_ADMIN_JOB_CACHE_ENABLED | 是否启用任务详情缓存 | true |
| TASKFLOW_ADMIN_JOB_CACHE_TTL | 详情有效期，从数据库查询开始计时，至少 1ms | 60s |
| TASKFLOW_ADMIN_JOB_CACHE_KEY_PREFIX | 详情 key 前缀，必须以 taskflow:admin: 开头、以冒号结尾 | taskflow:admin:job:detail:v1: |
| TASKFLOW_ADMIN_JOB_CACHE_EVICTION_ATTEMPTS | 提交后删除的总尝试次数，范围 1–3 | 3 |

缓存参数统一位于 application.yml 的 taskflow.jobs.detail-cache 下，可以在被忽略的本机 YAML 中覆盖。本机文件已开启 Redis 健康检查。spring.data.redis 的连接超时 2 秒、命令超时 1 秒；读和回填各尝试一次，只有提交后的缓存删除有限重试。超时按每次连接/命令计算，一次请求可能执行多个 Redis 命令，不承诺整个请求在 1 秒内完成。关闭健康检查不会禁用连接工厂或业务缓存；关闭缓存请使用单独的 TASKFLOW_ADMIN_JOB_CACHE_ENABLED=false。

缓存内容为 JSON，包含任务快照、配置版本和绝对过期时间，不序列化 Java 类型信息。不存在、过期、JSON 损坏或任务 ID 不匹配均视为未命中；不缓存空值。回填只设置剩余 TTL，过慢的查询不回填。写操作提交后同步删除对应 key；回滚和版本冲突不删除。删除重试耗尽记录错误并依靠 TTL 淘汰，不回滚或伪装已提交的数据库写入失败；本阶段没有持久化补偿队列。

删除失败或并发旧查询回填可能短暂返回旧详情，包含刚删除的任务，直到原查询开始后 TTL 到期。多实例需保持时钟同步；缓存命中不用于修改、启停、删除或未来派发的业务判断。缓存异常日志只记录 jobId、尝试次数和异常类型，不记录任务参数、凭证或异常正文。

## 启动和验证

从项目根目录启动 Admin，加载新的连接配置：

```powershell
.\mvnw.cmd -pl taskflow-admin spring-boot:run
```

Admin 健康检查仍为 http://localhost:18080/actuator/health；本机开启 Redis 检查后，Redis 不可用会使整体健康状态变为 DOWN，但详情查询仍可降级访问 MySQL，其他数据库接口继续工作。健康端点不公开连接参数或密码。缓存命中时无需连接数据库；未命中且数据库不可用时仍返回约定的 503。

可以使用脚本启动隔离测试上下文，执行 Java 客户端只读连接检查：

```powershell
.\tools\check-redis.ps1
```

该脚本通过 Spring 加载同一份 YAML 或环境变量，由 Lettuce 认证并发送 PING；测试数据库使用 H2，不写用户 MySQL 或 Redis 业务数据。它不验证缓存读写权限或业务缓存行为。

Java 客户端实际连接的集成验证使用测试 H2 数据库，并显式开启远程 Redis 测试：

```powershell
$env:TASKFLOW_REDIS_CONNECTION_TEST = 'true'
.\mvnw.cmd -pl taskflow-admin '-Dtest=RedisConnectionIntegrationTests' test
Remove-Item Env:TASKFLOW_REDIS_CONNECTION_TEST
```

默认 Maven 测试跳过远程测试，不需要 Redis。常规服务测试和前端隔离测试关闭缓存与 Redis 健康检查；缓存应用集成测试通过 test-cache profile 启用缓存，替换 StringRedisTemplate 并保留真实 H2 事务，验证 HTTP、提交与回滚行为。

测试数据库与 Redis 健康开关统一写在 application-test.yml，浏览器测试使用额外的 application-e2e.yml。远程测试先加载 test-redis-connection 导入本机 YAML，再加载 test 覆盖 SQL，缓存测试最后加载 test-redis-cache。IsolatedH2ContextInitializer 在创建 DataSource / 执行脚本之前拒绝非 H2 配置或开启生产 Flyway 的配置，发送 Redis 命令前还会核对实际 SQL 连接。Java 测试类不内置数据库连接参数。

此前只读连接验证已通过：Java 连接工厂使用 YAML 中的认证配置，PING 返回 PONG，SQL 使用独立 H2。新增缓存验证结果单独记录于 [缓存验证记录](cache-verification.md)。

## 真实 Redis 缓存验证

显式开启后，测试 SQL 仍使用独立 H2，Redis 加载本机 YAML 的连接配置，使用 test-redis-cache YAML profile 中的随机前缀 taskflow:admin:it:detail:<UUID>: 和 2 秒 TTL。只读写本次创建的精确 key，结束后逐个删除；不执行 KEYS、SCAN、FLUSHDB 或 FLUSHALL。启动前会检查数据库 URL、测试前缀与 TTL。

```powershell
$env:TASKFLOW_REDIS_CACHE_TEST = 'true'
.\mvnw.cmd -pl taskflow-admin '-Dtest=JobDetailRedisIntegrationTests' test
Remove-Item Env:TASKFLOW_REDIS_CACHE_TEST
```

此项验证真实 GET/SET/DEL、JSON 往返、毫秒 TTL 与自动过期、提交后失效和损坏缓存恢复；Redis 不可用与删除失败通过默认隔离测试注入，不停止或破坏用户 Redis。测试结果不代表对生产 MySQL/Redis 联动进行过写入验收。

## 参考

- [Redis AUTH：用户名可选，省略时使用默认用户](https://redis.io/docs/latest/commands/auth/)
- [Spring Boot Redis 连接属性](https://docs.spring.io/spring-boot/appendix/application-properties/)

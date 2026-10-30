# Admin 任务详情缓存验证

验证日期：2026-10-26。范围为 Admin 详情缓存、提交后失效与 Redis 故障降级。Scheduler / Worker 仍只有启动骨架，未加入派发或执行逻辑。

以上范围为 2026-10-26 缓存验证时的状态；后续三服务手动执行已实现，见 [执行验证](execution-verification.md)。执行权威状态仍不使用 Admin 展示缓存。

本文保留 2026-10-26 验证时的日志和测试类名称。Controller / Service / Mapper 结构中的对应测试为 job/mapper/redis/RedisJobCacheMapperTests，原名为 RedisJobDetailCacheTests；最新完整回归和目录见 [结构调整记录](structure-verification.md)，缓存语义保持不变。

## 已实现行为

- GET /api/v1/jobs/selectById 先读 Redis，未命中查数据库并回填，不改变现有 JSON 协议。
- 默认 key 为 taskflow:admin:job:detail:v1:<id>，TTL 60 秒，从数据库查询开始计算。缓存内容带绝对截止时间；延迟回填只使用剩余 TTL，耗尽则跳过回填。
- 写用例始终查询数据库，成功提交后通过 AFTER_COMMIT 事件删除缓存。回滚、版本冲突不删除；删除最多尝试三次，耗尽后记录错误并依靠 TTL 淘汰。
- Redis 读取或回填失败不影响正常数据库结果；数据库未命中不缓存。分页和统计不使用详情缓存。
- 若详情查询处于其他本地事务内，直接查询数据库，不读缓存或回填未提交数据。

配置见 [Redis 缓存配置](redis.md)，协议和一致性边界见 [API 文档](api.md) 与 [访问流程](request-flow.md)。

## 根目录构建与隔离测试

实际命令：

```powershell
.\mvnw.cmd -o -B '-Daether.syncContext.named.factory=rwlock-local' -l .tools/verification/admin-cache-root-verify.log verify
```

结果：BUILD SUCCESS，44 项通过、4 项远程 Redis 检查默认跳过，无失败或错误。三个服务均生成独立可执行 JAR。离线参数复用已有 Maven 依赖；rwlock-local 为验证环境的 Maven 仓库锁配置，不影响业务配置。

| 测试组 | 通过 | 默认跳过 | 覆盖 |
| --- | --- | --- | --- |
| RedisJobDetailCacheTests | 10 | 0 | JSON/UTC/空字段往返、剩余 TTL、慢查询不回填、绝对过期、损坏/错 ID、故障降级、有限删除重试、缓存关闭、非法配置 |
| JobDetailCacheIntegrationTests | 10 | 0 | HTTP 首查回填与再查命中、写后失效、独立连接确认提交、回滚、版本冲突、故障注入、并发旧快照回填、分页统计不走缓存 |
| Admin 原有接口、数据库故障和启动测试 | 20 | 0 | 任务管理 16、数据库故障 1、基础启动与接口 3 |
| Scheduler / Worker 启动与边界测试 | 4 | 0 | 各服务独立健康检查与管理资源隔离 |
| 真实 Redis 缓存与只读连接检查 | 0 | 4 | 按环境开关显式启用，单独结果见下文 |

默认测试使用独立 H2。缓存应用集成测试保留真实数据库事务、HTTP 接口和 Redis 适配，仅替换 StringRedisTemplate；通过仓储调用计数确认命中不查库，通过另一条 SQL 连接确认删除缓存发生在提交之后。

并发场景用两个线程和同步闩锁固定顺序：读到旧数据库快照 → 写入新版本并失效 → 旧查询回填。验证允许短时读到旧展示版本，但原过期截止时间不延长，写用例仍使用数据库新版本，后续成功失效可清除旧缓存。到期重查由下方真实 Redis 测试验证。

## 真实 Redis 验证

实际命令：

```powershell
$env:TASKFLOW_REDIS_CACHE_TEST = 'true'
$env:TASKFLOW_REDIS_CONNECTION_TEST = 'true'
.\mvnw.cmd -o -B '-Daether.syncContext.named.factory=rwlock-local' -pl taskflow-admin '-Dtest=JobDetailRedisIntegrationTests,RedisConnectionIntegrationTests' -l .tools/verification/admin-cache-redis-tests.log test
Remove-Item Env:TASKFLOW_REDIS_CACHE_TEST
Remove-Item Env:TASKFLOW_REDIS_CONNECTION_TEST
```

结果：BUILD SUCCESS，4 项全部通过，无跳过。

| 验证 | 结果 |
| --- | --- |
| 使用本机 YAML 认证并 PING | PONG；SQL 连接确认为独立 H2 |
| 首查回填、二次命中 | 真实 SET/GET 往返成功，两次查询只读取一次数据库 |
| TTL 和自动过期 | 实际剩余 TTL 在 1–2000ms；等待 Redis 自动删除后再查，数据库读取次数增加到两次 |
| 修改、启用、停用、删除 | 每次提交后对应真实 key 不存在；重新读取获得新值，删除后返回任务不存在且不缓存空值 |
| 损坏 JSON | 回退数据库，并以正常快照替换损坏值 |

真实测试使用已配置 Redis 上随机独立前缀 taskflow:admin:it:detail:<UUID>:，仅操作测试创建的精确 key，并在结束后逐个删除；不执行扫描、清空数据库或停止 Redis。SQL 使用 H2，不对业务 MySQL 执行缓存验收写入。远程测试在创建任何数据源和执行迁移/建表之前校验 H2 与 Flyway 配置，避免本机 YAML 的加载优先级覆盖测试隔离。

## 验证限制

- 失效失败和并发旧回填可能造成短时旧详情，包括刚删除的任务；默认有效期截至原查询开始后 60 秒，多实例需要时钟同步。这不是强一致性，也不限制已在途请求的总耗时。
- 缓存失效未引入持久化补偿任务；提交后进程崩溃或删除重试耗尽时由 TTL 恢复。Redis 故障与重试耗尽通过异常注入验证，未对业务 Redis 注入故障。
- Redis 健康检查与缓存开关独立；启用 Redis 健康检查时，Redis 故障仍会使健康端点 DOWN，即使业务查询可以降级。
- 未对真实 MySQL 与 Redis 联动执行可写验收，也未做缓存性能基准或重新执行浏览器测试。
- Admin 需重新部署并启动才能加载缓存实现；验证未替换业务进程。

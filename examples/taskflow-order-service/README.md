# 订单超时关闭与库存示例

我把订单和库存预占归入独立业务服务，使用同一本地事务保证业务正确性。TaskFlow 只通过 HTTP 发起超时检查，不访问订单数据库。第一版每笔订单购买一种 SKU，默认付款窗口为 30 分钟，创建时写入 `expires_at`；本接口是模拟付款，不对接支付渠道或资金系统。

## 启动

前置条件：Java 21、MySQL 8 InnoDB，以及启用缓存时的 Redis。先用数据库管理员创建独立 `taskflow_order` schema 和仅拥有该 schema 权限的 `taskflow_order` 账号。不要复用 Admin/Scheduler/Worker 的数据库账号。Flyway 首次启动自动执行本模块迁移。

在仓库根目录创建被忽略的 `config/taskflow-order-local.yml`，填写实际连接配置和两个不同的随机令牌；示意字段如下，值须由本机配置提供：

```yaml
spring:
  datasource:
    url: ${TASKFLOW_ORDER_DB_URL}
    username: ${TASKFLOW_ORDER_DB_USERNAME}
    password: ${TASKFLOW_ORDER_DB_PASSWORD}
order:
  api-token: ${TASKFLOW_ORDER_API_TOKEN}
  worker-token: ${TASKFLOW_ORDER_WORKER_TOKEN}
```

在根目录运行：

```powershell
.\mvnw.cmd -pl examples/taskflow-order-service -am package
java -jar examples/taskflow-order-service/target/taskflow-order-service-0.0.1-SNAPSHOT.jar
```

服务默认只监听 `127.0.0.1:8083`。本机启动其他平台服务的步骤见根 README。Worker 设置 `TASKFLOW_ORDER_HANDLER_ENABLED=true`、`TASKFLOW_ORDER_URL` 和相同的 `TASKFLOW_ORDER_WORKER_TOKEN`，重启后节点会注册 `orderTimeoutCheck`。Handler 默认关闭，避免未接入订单服务的节点宣称具备该能力。cloud profile 下 Worker 按 `taskflow-order-service` 服务名发现，订单服务使用 `TASKFLOW_REGISTRY_URL` 注册。

控制台选择订单模板，参数为 `{"batchSize":100,"maxBatches":10}`。任务可按每分钟 Cron 执行；订单通常在到期后的下一轮关闭，积压和故障会进一步延迟。修改 Cron 不改变既有订单期限。模板保持停用，确认业务服务接入后再启用；重试次数由任务配置明确指定。

接口、令牌及示例请求见 [API 文档](../../docs/api.md) 的订单示例章节。先初始化 SKU，再创建订单；可通过订单详情的 `ledger` 核对 `HELD / CONSUMED / RELEASED`。没有提供库存任意覆盖接口，初始化相同 SKU 会冲突。

## 事务与故障边界

我先插入唯一的下单请求键，再以 `available >= quantity` 条件更新库存，创建预占和流水、写入缓存同步 Outbox，一起提交。相同请求键和参数返回原订单；更换购买参数返回 409。数量必须为正。库存扣减失败时整笔创建回滚。

付款和关单按订单主键执行 `FOR UPDATE`，再转换 `HELD` 预占及更新库存。付款严格要求当前时刻早于截止时间；已付款请求重复确认返回原结果，已关闭或过期则返回 409。付款只减少预占量，关单同时返还可用量。订单、库存、预占、唯一业务流水和 Outbox 一起提交；不宣称真实外部支付副作用恰好执行一次。

超时查询使用 `(state, expires_at, id)` 索引和键集游标，以本轮第一次请求的服务端时间为 cutoff。每个订单一个短事务，锁内重新检查状态与期限。失败订单计入 `failed`，游标继续前进；Worker 批量结束后将部分失败报告为可重试，后续 Attempt 从头重扫。达到批数或执行预算后结束本轮，剩余订单留到下一轮。没有候选也是成功；成功不能证明所有积压订单已经处理。流水保存 Run 关联请求 ID，但库存幂等依赖稳定的订单预占键，人工新建 Run 也不会重复释放。

HTTP POST 不自动重放。Worker 的 Feign 客户端使用 YAML 超时、现有 Resilience4j 熔断和 LoadBalancer；400/401/403/409 等客户端错误不自动重试，408/429/5xx、传输失败允许 Scheduler 在持久化 Attempt 预算内重试。订单服务提交后响应丢失时，结果暂时未知，重新扫描仍受数据库幂等保护。执行资格检查与远程事务不能原子提交，旧请求可能完成，但不能重复释放库存。

## Redis 查询加速

我使用 Redis Hash 保存库存快照，Lua 比较版本后原子写入，TTL 默认 30 秒。每次数据库写入提交后尝试删除缓存；同事务 Outbox 在提交后查询当前库存并刷新缓存，默认最多 10 次、间隔 5 秒，耗尽记录保留在 `stock_outbox` 供排查。恢复 Redis 后可在维护窗口重置耗尽行的 attempts/next_attempt_at 重新对账；查询未命中也会回填。关闭缓存后 Outbox 保留，重新开启可继续同步。

缓存删除、回填和数据库提交不是原子操作，并发回填、键过期或失效失败仍可能产生短暂旧快照，依靠 TTL/Outbox 收敛。Redis 不参与库存预扣；缓存显示有货也可能下单失败。Redis 断连时库存查询回退 MySQL，下单始终走 MySQL 条件扣减。不要通过 Redis 数量判断是否允许创建订单。

## 验证与恢复

```powershell
.\mvnw.cmd -pl examples/taskflow-order-service test
.\tools\test-order-mysql.ps1
# 先在根目录完成 verify，生成三个服务的 JAR，再执行进程故障验收：
.\tools\test-order-mysql.ps1 -IncludeFlow
# RedisServerPath 指向本机 Redis 可执行文件：
.\tools\test-order-redis.ps1 -RedisServerPath $env:REDIS_SERVER_PATH
```

MySQL 脚本要求 PATH 中有 mysqld/mysql/mysqladmin；在 `.tools/order-mysql` 新建临时实例，只监听回环端口 13389，结束时停止进程，保留数据和日志证据。进程验收还需要 Node，启动 Scheduler、Worker、订单服务与故障代理，默认使用 19380–19383；订单用独立 MySQL，平台用各自 H2 文件。不会复用业务数据库。不要把此测试配置用于部署。

启动失败先检查对应日志、端口、schema 权限和令牌；迁移失败不应跳过校验或自动清库。进程故障测试在 finally 中关闭所启动的进程。真实支付、独立仓储补偿、Redis 前置预扣以及跨机器故障仍不属于此示例的保证范围。

当前验证日期、环境、证据和未验证范围见 [订单验收记录](../../docs/order-verification.md)。

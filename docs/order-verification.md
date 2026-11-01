# 订单超时关闭与库存验证

## 2026-11-01 本机验证

环境为 Windows、Java 21.0.12.1、Spring Boot 4.1.1、独立 MySQL 8.0.34（InnoDB，REPEATABLE READ）及独立 Redis 7.4.8。数据库与 Redis 只监听本机回环地址；测试新建隔离数据库，不使用本机业务账号或业务数据。

| 命令 | 结果与证据 |
| --- | --- |
| 根目录 `.\mvnw.cmd -B verify` | 七个服务模块构建成功；包含订单 H2 回归与现有服务回归。日志 `.tools/order-root-final-verify.log`，2026-11-01 11:49:33 +08:00 完成 |
| `.\mvnw.cmd -B -pl taskflow-worker verify` | 最终 HTTP 传输实现通过；81项中80通过、1项外部 Broker 测试条件跳过。日志 `.tools/order-worker-transport-verify.log` |
| `.\mvnw.cmd -B -pl taskflow-worker -Dtest=OrderRemoteIntegrationTests test` | 1项通过：使用 SimpleDiscoveryClient 的服务名发现、真实 HTTP 调用与能力注册；日志 `.tools/order-remote-discovery-tests.log` |
| `taskflow-web` 中 `npm test` | 23项全部通过，包含订单模板参数及草稿隔离 |
| `.\tools\test-order-mysql.ps1 -IncludeFlow` | MySQL 8项全部通过；三进程故障验证见下表。日志 `.tools/order-mysql/20261101-115431-809/tests.log` |
| `.\tools\test-order-redis.ps1 -RedisServerPath <本机Redis路径>` | 1项通过：真实 Lua 拒绝旧版本覆盖并验证 TTL 过期；日志 `.tools/order-redis/20261101-114710-923/tests.log` |

MySQL 测试验证了100个独立请求争抢10件库存只成功10笔；24个同键并发请求只预占一次；同键不同参数冲突；未到期、已付款与已过期订单正确区分；付款与关单并发保持合法终态；库存更新失败时订单/预占/流水回滚；创建中途失败不残留库存扣减；丢失批次结果后从头重扫；失败候选不阻挡后续页面。

Redis 故障降级及错误正库存快照通过故障注入回归验证：缓存读、写、失效均抛错时数据库仍可处理；即使缓存声称有货，MySQL 库存不足仍拒绝下单。此项与真实 Redis Lua/TTL 测试分别执行。

## 三进程故障证据

进程验收启动独立 Scheduler、Worker、订单服务以及 HTTP 故障代理。订单服务使用上述独立 MySQL，Scheduler/Worker 各使用独立 H2 文件。订单付款窗口为测试专用2秒，批量大小2，单次上限10批；TaskFlow 手动创建 Run，最多3次 Attempt，重试间隔10秒。原始证据保存在 `.tools/order-flow/20261101-115457-542/evidence.json`，同目录保留进程日志及响应丢失标记。

| 场景 | 验证条件 |
| --- | --- |
| 多批次 | 7笔过期订单跨至少4批处理，全部 CLOSED、预占归零、库存完整返还 |
| 人工重跑 | 新 Run 重扫相同订单，每笔仍只有一条 RELEASED 流水 |
| 订单服务停机 | 记录失败 Attempt 后重启业务服务，后续有限重试成功，无重复释放 |
| 已提交但响应丢失 | 代理确认服务已返回200再断开响应；Run 必须新增持久化 Attempt 后成功，禁止传输层静默重放掩盖失败 |
| Worker 中途退出 | 代理确认首批已提交并挂起响应，杀死 Worker；重启后经租约/Attempt 恢复完成本轮7笔订单中的剩余部分，每笔只释放一次 |

验收脚本对每个场景同时检查 Run 成功、订单终态、库存可用量与预占量、唯一释放流水。最终 JSON 记录各场景 Run/Attempt 和订单ID，可以对应订单业务流水查询。

## 验证范围

默认 `verify` 有条件跳过真实 MySQL/Redis/Broker 专项，不能将这些跳过项计为通过；本次 MySQL 和 Redis 使用专用脚本分别实测。订单专项的进程派发使用 HTTP；现有 RabbitMQ 消费链路随平台单元回归执行，但未实测订单专项跨 Broker 故障。服务名发现采用本机静态实例集成验证，本轮不声称重新完成 Eureka/配置中心集群验收。真实支付、资金扣款、迟到支付退款、跨服务库存补偿及跨机器网络分区未验证。

所有原始日志、临时数据库、测试 JAR 和进程输出位于忽略的 `.tools`，不纳入 Git。重复验证会创建新的日期目录；本记录指向2026-11-01本次执行证据。

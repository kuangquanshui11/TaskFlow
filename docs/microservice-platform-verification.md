# 微服务技术栈验证记录

日期：2026-10-27。范围：Gateway、Eureka、Config Server、OpenFeign/LoadBalancer、Resilience4j、Micrometer OTel/OTLP，以及既有业务闭环回归。架构和操作见 [平台文档](microservice-platform.md)。

## 根构建

执行 `mvnw.cmd verify`，六个应用均生成独立可执行 JAR，BUILD SUCCESS。统计来自各模块 Surefire XML：

| 模块 | 总数 | 通过 | 条件跳过 | 失败/错误 |
| --- | ---: | ---: | ---: | ---: |
| Registry | 1 | 1 | 0 | 0 |
| Config Server | 1 | 1 | 0 | 0 |
| Gateway | 6 | 6 | 0 | 0 |
| Admin | 69 | 64 | 5 | 0 |
| Scheduler | 58 | 58 | 0 | 0 |
| Worker | 20 | 20 | 0 | 0 |
| 合计 | 155 | 150 | 5 | 0 |

五项跳过为真实 Redis 连接 1 项、真实 Redis 缓存 3 项、Admin 专用 MySQL 迁移 1 项，不计入通过。日志为 `.tools/platform-final-verify.log`。

初次回归还发现 Admin 同步扫描器停机等待导致 Surefire 强制终止测试 fork；已改用 Spring 协调停止并取消未来扫描。补充两项真实调度器测试：未来扫描不拖延退出、上下文关闭允许当前发送正常完成。Admin 最终单模块回归正常退出，69 项中 64 项通过、5 项跳过，没有再出现强制退出提示。该验证不代表所有服务的复杂生产停机场景已经覆盖。

新增/重点测试：

- Registry/Config Server 真实 HTTP：匿名拒绝、认证读取成功、健康公开、配置内容真实返回且不包含访问密码。
- Gateway 两个真实上游：服务名负载均衡、路径/正文保留、请求 ID、内部 token 删除、内部/注册/配置/管理路径及歧义路径隔离、超大请求拒绝、503 熔断打开与恢复、POST 无重放、W3C 新 span 传播。
- Admin Feign：失败请求只发送一次，熔断后不访问上游，半开恢复，404 不触发可用性熔断；真实 MVC 请求通过 Feign 传播 trace，并实际发出 OTLP HTTP 数据。
- Scheduler 实际 LoadBalancer 子上下文：正确解析客户端名，只保留匹配固定 `worker-id` 的实例；缺少身份或其他 Worker 不可选，无目标返回空列表。
- 既有配置、计划、Cron、Run/Attempt、消息边界、节点及结果上报测试全部回归。

## 六个独立应用与浏览器

执行 `tools/test-platform.ps1 -SkipBuild`，最终退出码 0。实际启动 Java 21 独立进程：Eureka 19161、Config Server 19188、Gateway 19088、Admin 19080、Scheduler 19081、Worker 19082，另有 loopback OTLP 测试接收器 19118 和 Nginx 8091。每个业务服务使用本轮独立 H2 文件库；随机凭据与 YAML 仅保存在忽略目录。

证据：

- `.tools/platform-process-test.log`
- `.tools/platform-e2e/20261027-111353-247/`：平台日志、隔离配置仓库和 OTLP 数据
- `.tools/execution-e2e/20261027-111416-266/report.json`：48 项均为 PASS，其中 47 项进程/API 检查、1 项整套浏览器通过记录
- `taskflow-web/playwright-report`：Chrome / Playwright 16 项全部通过
- 前端单元测试 9 项通过，Vite build 成功

实际验证了四个应用都出现在 Eureka、三个业务服务通过 Config Server 取得 `governance-v1` 标记。Gateway 使用 cloud profile，无 local 静态实例兜底；内部 Feign 使用逻辑服务名，成功完成手动执行、失败重试、超时、幂等、快照和旧租约拒绝。

故障验证包含 Scheduler/Worker 重启后的待派发恢复、Scheduler 停机时配置保存不阻塞、有限 Outbox 重试及人工恢复、Admin 重启保留失败事件身份、删除墓碑及乱序重复事件、计划持久化、Cron 到期真实执行和停用后停止触发。节点验证包含心跳过期、离线阻断派发且不消耗租约、新进程 generation 更新、恢复原 Attempt、Scheduler 停机查询返回 503。

修正了两处真实集成问题：Config 导入在 profile 引导阶段需要提前提供隔离中心地址；Worker LoadBalancer 子上下文必须使用框架的 `loadbalancer.client.name` 属性。故障注入也改为先确认节点心跳过期，再创建真正待派发任务，避免把已领取执行租约的请求误算为未派发恢复。

## Trace 与网关发行包

发送采样 W3C traceparent，测试接收器保存实际 OTLP protobuf。以下同一 Trace ID 的 Gateway、Admin、Scheduler 资源均被接收到：

`d70da05fb3a24221a7084d40075addaa`

安全摘要见平台证据目录的 `traces/<traceId>.json`，包含服务名与 PASS。另对本轮 OTLP 文件检查确认 Gateway、Admin、Scheduler、Worker 四个应用均实际导出了资源数据；Worker 后台心跳使用自己的 trace，不把它误算为上述同步请求。该接收器只用于验收，不替代生产追踪后端；没有把依赖存在或请求头透传当作完整追踪验证。

执行 `tools/package-frontend.ps1 -SkipBuild`，生成新包 `dist/releases/20261027-111259-714/taskflow-nginx` 及 ZIP/SHA256。生成配置已确认上游 `127.0.0.1:8088`、`proxy_pass http://taskflow_gateway`、禁止上游重试，Nginx `-t` 通过。没有切换 `dist/current-release.txt`。全部 PowerShell 脚本语法解析通过。

## 尚未实测或交付的边界

- 本机无 Docker，Registry/Config/Jaeger Compose 尚未运行；Java 进程模式已实测。Jaeger UI、持久存储、Eureka/Config HA 不计入通过。
- RabbitMQ 三节点集群仍待真实环境验收；本轮进程回归显式使用 HTTP 派发，没有冒充 MQ 实测。
- 同步 HTTP trace 已验证；持久化 Outbox/MQ/Inbox 尚未持久化原 trace 上下文，异步执行依靠 Run/Attempt 标识关联。
- 配置默认启动加载、修改后重启；没有完整动态刷新或 Bus。
- 网关入口隔离不等于用户认证/RBAC，公网 TLS 与业务权限后续实现。
- 未重启现有业务实例，未迁移真实业务 MySQL，未激活新 Nginx 发行包。测试结束清理本轮所属 PID，保留证据；现有环境升级步骤见平台及前端部署文档。

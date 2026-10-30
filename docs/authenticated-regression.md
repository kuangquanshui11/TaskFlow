# 带登录的任务链路回归

## 验收边界

我把登录安全与任务执行放入同一套隔离验收环境。管理请求携带真实 Keycloak 签发的 JWT，依次经过 Gateway 验签与 Redis 限流、Admin 验签与 Redis 会话，再进入既有任务用例。测试不关闭安全过滤链，不装载测试会话存储，也不通过密码授权或自行签名代替交互登录。

`tools/test-execution.ps1` 同时供 HTTP、MQ、平台和前端验收复用。MQ 完整入口为 `tools/test-mq.ps1`；其创建随机 `taskflow_it_` vhost，不改动业务队列。测试 Keycloak 使用独立发行目录副本、数据库、随机 Realm 和随机 ADMIN/VIEWER 密码。Admin 会话与 Gateway 限流分别运行密码保护的 Redis 进程，不共用业务 Redis，不执行 FLUSHDB/FLUSHALL。

## 前置条件与运行

需要 Java 21、PowerShell 7、Node 与已安装的前端依赖、Chrome、解压的 Keycloak、Redis 6.2+ server/cli，以及现有 Nginx 发行目录。Nginx 由 `tools/package-frontend.ps1` 准备。Redis server 与同目录 redis-cli 由 `TASKFLOW_TEST_REDIS_SERVER` 指定；默认也可从 PATH 或忽略目录 `.tools/redis-*/*/redis-server.exe` 查找。Keycloak 目录由 `TASKFLOW_TEST_KEYCLOAK_HOME` 指定，默认 `.tools/access/keycloak-26.8.1`。脚本只复制运行库、配置、providers/themes，不复制已有身份数据。

空闲端口：Admin 19080、Scheduler 19081、Worker 19082、Gateway 19088、Nginx 8091、Keycloak 19190，以及 Redis 16379/16380。占用时脚本拒绝运行，不终止已有服务。

```powershell
# 使用本机发行目录设置 TASKFLOW_TEST_REDIS_SERVER / TASKFLOW_TEST_KEYCLOAK_HOME。
.\mvnw.cmd verify
.\tools\test-mq.ps1 -SkipBuild -SkipFailover -ClusterSettingsPath .tools/mq-cluster-settings.json
```

外部集群 JSON 使用 `addresses`、`username`、`password` 和三个 `managementUrls`，格式与 `tools/connect-mq.ps1` 相同，包含凭据的文件只放忽略目录。三节点须全部运行。RabbitMQ 低于 3.10 时必须显式加 `-AllowLegacyBroker`；它仅验证当前版本的派发链路，不能验收可靠死信转移。`-SkipFailover` 只省略 Broker 节点故障注入，Redis 故障、身份权限和 Cron/手动执行仍会运行。需要验证已有 Broker 故障流程时沿用 `-ManualFailover`。

仅验证 HTTP 派发时执行 `.\tools\test-execution.ps1 -SkipBuild`。`-SkipBrowser` 省略控制台交互，但获取 JWT 仍使用 Chrome 完成真实交互式 PKCE 登录。无论哪种模式，脚本都启动测试 Redis，不依赖人工启动 Redis。前端验收不再接受匿名管理请求，需使用这些入口。

连接参数、issuer、会话策略在 `tools/acceptance/application-access-*.yml`，动态凭据由子进程环境注入，结束时恢复父进程环境。会话 idle-timeout 保持 30 分钟；只有测试 login-window 缩短为 5 秒。会话过期检查只对当前测试 JWT 对应的 key 设置短 TTL，然后等待真实 Redis 到期，不能将此加速实验表述为等待了 30 分钟。

## 覆盖与证据

1. ADMIN 真实登录、授权码与 PKCE 兑换、Gateway 准入、Redis 会话创建。
2. 创建 Job、Outbox 配置同步、Scheduler 版本副本、手动与 Cron Run、MQ、Worker Inbox/Handler/结果上报、带 JWT 查询 Run/Attempt/快照。
3. 匿名及签名篡改拒绝；有效 JWT 缺少会话仍拒绝；VIEWER 可查询、写入和触发在 Gateway/Admin 直连均拒绝。
4. 真实 Redis 保存 JWT、30 分钟 TTL 及滑动续期；真实令牌刷新与会话轮换；退出后新旧令牌及登录重放拒绝；TTL 到期后请求、旧登录与轮换拒绝，重新输入密码可登录。
5. 分别停止限流 Redis 和会话 Redis，请求返回 503；对有效 Job 的创建 Run 请求不进入 Scheduler。恢复后显式提交新请求，继续经 MQ 完成执行。失败 POST 不自动重放。
6. Nginx 正式模板、登录后的控制台任务管理、执行详情、响应丢失幂等、真实登录/退出/TTL 过期浏览器检查。

证据写入忽略目录 `.tools/execution-e2e/<日期时间>/`：`report.json` 汇总任务和安全检查，`security-*.json` 保存细项，`*.out.log` / `*.err.log` 保存独立服务日志，PNG 保存浏览器截图；`.tools/mq-e2e/<随机 vhost>/report.json` 记录 MQ 阶段与关联证据目录。只有全部必需检查通过后才生成完成报告。账号、完整 JWT 和刷新令牌仅存于忽略的本轮配置与令牌文件，不进入验收报告。

失败时检查本轮日志，释放端口或恢复集群后重新执行。脚本在 finally 中停止自己创建的服务、身份服务与 Redis；MQ 失败时保留本轮测试 vhost 供排查，成功时只删除本轮随机 vhost。测试数据文件保留在 `.tools`，不会替代业务数据备份，也不会切换正式服务或发行包。

2026-10-30 的环境、命令、结果和未验证范围见 [带登录的安全与任务链路验证](authenticated-regression-verification.md)。

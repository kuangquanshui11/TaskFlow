# 登录、JWT 权限与入口限流

更新：2026-10-28。

## 身份与服务边界

账号、密码、登录、角色分配和令牌签发由独立 Keycloak 管理。TaskFlow 不新增用户表、密码表或跨服务 Repository。Keycloak 不加入 Maven 应用依赖；其生产数据库和账号应单独管理。

浏览器在 TaskFlow 登录页点击“登录 TaskFlow”，跳转身份服务，使用 OIDC Authorization Code + PKCE S256。登录回调由官方 keycloak-js 处理 state、nonce 和 code 兑换。访问令牌和刷新令牌只保存在适配器内存，不写 localStorage/sessionStorage。刷新页面时检查身份服务 SSO，并恢复尚未失效的 Redis 会话；过期后必须重新输入密码，不能仅依靠 SSO 或刷新令牌重建。

业务请求使用 Authorization: Bearer <access_token>。发出请求前刷新临近过期的令牌；刷新失败或 API 返回 401 时卸载控制台、清理本地会话并显示重新登录。请求不会因 401、429、超时或网关故障自动重放，尤其不会自动重新创建 Run。未确认 Run 的浏览器幂等记录按用户 subject 隔离。

Gateway 与 Admin 各自校验 JWT：只接受 RS256、可信 issuer、包含本 API 的 audience、非空 subject、存在的 exp，以及 exp/nbf 时间窗口；随后 Admin 检查 Redis 中的有效会话。Spring Security 默认时钟容差为 60 秒。运行时通过 issuer 的 /protocol/openid-connect/certs 读取并缓存 JWKS，支持签名密钥轮换。配置的 issuer 必须与浏览器访问 Keycloak 获得的 iss 完全一致，不能混用 localhost 与 127.0.0.1。

Admin 是授权的最终执行方，直接访问 Admin 也必须提供有效令牌。权限来自 resource_access.<audience>.roles，仅识别本 API 的 VIEWER、OPERATOR、ADMIN；其他 client、realm 角色或任意 scope 不授予业务权限。Controller 前的过滤链按方法和精确路径授权；Job 写入、启停、删除、Run 创建和同步重试再做 Service 方法授权。未配置的新路径或 HTTP 方法默认拒绝。原 Admin 内嵌演示首页关闭，正式登录入口由 Nginx 的 Vue 控制台提供。

Scheduler / Worker 保持原有独立服务间密钥。Admin 发往 Scheduler 的请求使用内部凭据，不将浏览器令牌作为内部控制凭据；Gateway 清除外部传入的内部 token 和身份声明头。

## 角色与接口权限

| 权限 | VIEWER | OPERATOR | ADMIN | 路径 |
| --- | --- | --- | --- | --- |
| job:read | 是 | 是 | 是 | jobs/select、selectById、selectSummary、preview；job-handlers/select；job-sync 两个查询；config-reconciliation 两个查询 |
| run:read | 是 | 是 | 是 | runs/select、selectById、selectByRequestKey |
| worker:read | 是 | 是 | 是 | workers/select、selectById |
| job:operate | 否 | 是 | 是 | jobs/enable、disable |
| run:trigger | 否 | 是 | 是 | runs/insert |
| job-sync:retry | 否 | 是 | 是 | job-sync/updateRetry |
| job-sync:reconcile | 否 | 是 | 是 | config-reconciliation/insert |
| job-sync:repair | 否 | 是 | 是 | config-reconciliation/updateRepair |
| job:write | 否 | 否 | 是 | jobs/insert、update |
| job:delete | 否 | 否 | 是 | jobs/delete |

所有业务路径前缀为 /api/v1/。preview 仍为 POST，但使用 job:read。前端按后端返回的权限隐藏无权操作按钮，后端始终独立授权。当前是整个 TaskFlow 实例范围的角色权限，不支持租户隔离或逐 Job 授权。

| 方法 | 路径 | 鉴权 | 响应 data |
| --- | --- | --- | --- |
| GET | /api/v1/access/selectConfiguration | 匿名 | issuer、clientId；均为公开登录参数，不包含私钥、密码或 client secret |
| GET | /api/v1/access/selectCurrent | 有效 JWT | subject、username、roles、permissions |

健康探针允许匿名，仅返回既有简要信息。其他业务 API（包括 hello）要求 JWT。

## 首次本地部署

1. 复制 deploy/access/.env.example 为同目录 .env，为 bootstrap 管理员设置独立强密码。不要提交此文件。
2. 从项目根目录执行 docker compose --env-file deploy/access/.env -f deploy/access/compose.yml up -d。Keycloak 默认仅监听宿主机 127.0.0.1:8180；首次导入 taskflow Realm。没有 Docker 时，可从官方发行版解压 Keycloak，使用 bin/kc.bat start-dev --http-port=8180 --import-realm，并把 Realm 文件放入其 data/import。发行版、运行数据和本机凭据放在忽略的 .tools 或其他本机目录。
3. 在 Keycloak 管理控制台创建实际用户、设置密码，在 taskflow-api 客户端下分配 VIEWER、OPERATOR 或 ADMIN。仓库不创建默认业务用户或预置业务密码，首次管理员需要手动创建。
4. 复制 config/taskflow-access.example.yml 为 config/taskflow-access-local.yml，设置实际公开 issuer。Admin 和 Gateway 都读取该忽略文件；cloud 配置中心只可保存非秘密策略。
5. 在 config/taskflow-gateway-local.yml 中配置 Gateway 自己的 spring.data.redis 连接和凭据。限流使用独立账号及 key 命名空间，不使用 Admin 查询缓存凭据。若本地共用 Redis 实例，仍需通过 ACL 隔离访问；单独 database 不能代替 ACL，Redis Cluster 使用 database 0。
6. 按 README 启动平台和业务服务，构建并更新 Nginx 前端发行包。访问 http://localhost:8090，点击登录后输入 Keycloak 用户的账号密码。

初始 Realm 允许 localhost / 127.0.0.1 的 8090（Nginx）和 5173（Vite）来源及回调路径；其他端口或生产域名必须在 Keycloak 明确添加，禁止通配来源。前端 client 是 public client，没有 client secret；禁用密码授权、隐式授权和自助注册。启用 PKCE、账号暴力尝试保护及至少 12 位密码策略。保留 basic 默认 client scope，确保访问令牌带 sub；只允许控制台声明 taskflow-api 的指定角色范围，配置固定 API audience。

Compose 使用 start-dev 和本地数据卷，仅用于本地开发。生产应部署独立身份数据库、HTTPS、正确公开 hostname、严格回调和来源，并保护管理控制台及登录入口；不要直接沿用 start-dev。本地模板不包含生产 TLS 证书，Nginx 和身份入口的证书需在部署环境中单独配置。

| 环境变量 | 作用 |
| --- | --- |
| TASKFLOW_JWT_ISSUER | 必填，公开 issuer，形式为 https://身份域名/realms/taskflow；两端一致 |
| TASKFLOW_JWT_AUDIENCE | 默认 taskflow-api，兼作客户端角色归属 |
| TASKFLOW_OIDC_CLIENT_ID | Admin 返回给浏览器的登录 client，默认 taskflow-console |
| TASKFLOW_JWT_PUBLIC_KEY_LOCATION | 可选，固定 RSA 公钥资源；测试使用专用测试公钥，部署时应留空以使用 JWKS 轮换 |
| TASKFLOW_GATEWAY_REDIS_HOST / PORT / USERNAME / PASSWORD / DATABASE | Gateway 自己的限流 Redis 连接；默认 localhost、6379、空凭据、database 1 |
| TASKFLOW_RATE_LIMIT_NAMESPACE | 默认 taskflow:gateway:v1；所有共享额度的 Gateway 实例必须相同 |

issuer 缺失时拒绝启动，没有默认放开的 local/cloud 模式。测试使用独立 YAML 和公开的测试 RSA 密钥，绝不能让部署环境信任测试密钥。

## 限流和故障行为

Gateway 使用 Reactive Redis + RequestRateLimiter，按可信 iss + sub 的 SHA-256 摘要及业务分组共享额度，不使用原始 Token、查询参数、客户端身份头或客户端 IP 作为登录用户的身份。多个实例须连接相同限流 Redis、使用相同 namespace 和策略。

| 分组 | 判定 | 补充令牌/秒 | 桶容量 |
| --- | --- | --- | --- |
| read | GET；POST jobs/preview | 20 | 40 |
| write | 其他 POST | 5 | 10 |
| execute | POST runs/insert 或 job-sync/updateRetry | 1 | 3 |

分组内接口共享额度，防止切换路径绕过。每个请求消耗一个令牌；这是允许短时突发的令牌桶，并非固定秒窗口。参数位于 Gateway application.yml 的 taskflow.rate-limit，默认重启生效。Redis Lua 原子扣减，键由内置脚本设置 TTL 回收。公开登录配置使用独立匿名 IP 桶；Gateway 只依据实际连接地址，不接受任意 X-Forwarded-For 充当限流身份。经过同一 Nginx 的匿名配置请求可能共享该桶。

内置 RedisRateLimiter 在 Redis 异常时会返回 allowed=true、剩余令牌 -1。本项目显式识别并拒绝该故障标记，并限制限流操作总等待时间为 2 秒。Redis 失败时公共 API 返回 503 / RATE_LIMIT_UNAVAILABLE，不会转发业务请求，也不会将失败变成成功。此时登录配置也可能不可用，前端显示错误和重新连接。健康探针不消耗限流额度。

Nginx 对 /api/ 增加每连接 IP 50r/s、burst 100 的粗粒度保护，覆盖未认证和无效 JWT 流量。该额度按单个 Nginx 实例计算。Nginx 覆盖转发头而非追加外部身份；后续增加上游代理时需独立配置可信代理规则。JWT 用户限流不替代 Worker 的资源限制、调度并发或 Run 幂等。

| HTTP | code | 含义 |
| --- | --- | --- |
| 401 | UNAUTHENTICATED | 缺少、无效或失效的令牌；包含 WWW-Authenticate: Bearer |
| 403 | ACCESS_DENIED | 有效身份缺少权限，或方法/路径未被允许 |
| 429 | RATE_LIMIT_EXCEEDED | Gateway 超限，带 Retry-After: 1 |
| 503 | RATE_LIMIT_UNAVAILABLE | 限流基础设施失败，拒绝派发 |

Gateway/Admin 返回现有 code、message、data、requestId 格式；Nginx 自己拒绝的请求可能为 HTML，前端仍按 HTTP 429 显示请求过快提示。请求关联信息在安全过滤链前生成。JWT、刷新令牌、密码、Authorization 请求头和轮换请求体不得写入业务日志。

访问令牌默认 5 分钟有效。TaskFlow 退出接口立即撤销对应 Redis 会话，其当前和轮换前的令牌均不能继续访问该会话。仅在 Keycloak 管理端改角色或禁用账号，不会直接删除 TaskFlow 缓存；权限变更仍可能延迟到令牌更新或会话到期。其他端的全局即时撤销仍需要单独的撤销事件或 introspection 机制。

## Redis JWT 会话：30 分钟滑动过期

Admin 的 access 模块拥有会话数据，使用本服务 Redis 和独立 key 前缀，Gateway 不读取 Admin 会话键。请求顺序为 Gateway 验签/限流 → Admin 验签 → Redis 会话检查与续期 → 接口权限与业务用例。JWT 通过验签但未命中有效缓存仍返回 401，Redis 故障返回 503，不回退到仅信任 JWT。

缓存 key 为 issuer、subject 和 Keycloak sid 的 SHA-256 摘要；Redis Hash 保存当前完整 JWT、登录 auth_time、状态，以及轮换时短暂保留的旧 JWT。会话 key 的 TTL 默认为 1800000 毫秒。每次通过会话检查的请求由 Lua 在一个原子操作中核对缓存令牌并 PEXPIRE 续期为 30 分钟；失败请求不创建已失效的 key。Keycloak 和 Admin 都必须保留 basic scope 提供的 sub、sid/auth_time。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| POST | /api/v1/access/insert | OIDC 回调后建立或恢复会话；必须先通过 JWT 校验。只有最近两分钟内重新认证的令牌能建立缺失会话；旧 SSO 令牌只能恢复仍存在的活动会话 |
| POST | /api/v1/access/update | Authorization 携带新 JWT，JSON 为 previousToken；仅原会话仍有效且匹配缓存令牌时轮换 |
| POST | /api/v1/access/disable | 撤销当前会话，保留有期限的撤销标记，阻止重放最近的登录令牌重新建立会话 |

前端内存保存适配器 JWT 和最近被缓存接受的令牌。刷新 JWT 后，先完成 Redis 轮换，再发业务请求；刷新并发共用一个 Promise。旧令牌仅保留 10 秒用于已发出的并发请求，并仍受 JWT exp/nbf 验证约束。新令牌不能通过轮换重新建立已过期会话。手动点击登录要求 prompt=login、maxAge=0，真正重新认证后才允许新建。

客户端刷新页面可用仍有效的同一身份会话重新进入控制台，原子替换缓存 JWT；此操作仅恢复活动缓存，不创建已过期缓存。连续 30 分钟没有有效请求，Redis key 自动过期，下次请求返回 SESSION_EXPIRED / SESSION_REAUTH_REQUIRED，前端卸载控制台并显示登录页。后台轮询也是请求，会延长会话；这里计算的是“距上次有效请求”，不计算键盘或鼠标闲置时间。

JWT exp 与 Redis TTL 分开管理：TTL 续期不能修改已签名的 exp；前端按需通过 Keycloak 刷新访问令牌。所有浏览器标签页共享同一 Keycloak sid 时，使用同一个 Redis 会话；并发轮换只提供上述短暂旧令牌宽限，不承诺多个标签页同时轮换永不重新登录。没有为机器 client_credentials 提供绕过交互会话的入口。

配置位于 Admin YAML 的 taskflow.access.session：idle-timeout=30m、login-window=2m、rotation-grace=10s；idle-timeout 必须大于 login-window，防止旧登录令牌在会话闲置过期后重新创建缓存。会话凭据具有访问能力，Redis 必须配置 ACL、网络隔离和按需 TLS；不把 JWT 写入业务缓存或集中配置仓库。部署多 Admin 实例时使用相同会话 Redis 和 key-prefix，任一实例的有效请求都可以续期。

会话创建/验证/轮换是单服务 Redis 操作，不与业务 SQL 原子提交。会话续期成功后业务仍可能因权限、校验、数据库故障失败；业务提交也可能先于前端收到响应，继续沿用既有 Run 幂等机制。

## 验证

根目录 .\mvnw.cmd verify；前端 npm.cmd test、npm.cmd run build。安全测试使用真正 RSA 签名验证缺失/篡改/过期 JWT、错误 issuer/audience、缺失 subject/exp、只读越权、Service 方法授权、默认拒绝、限流 429 与 Redis 连接失败的拒绝行为。旧业务测试携带测试 JWT，不关闭安全过滤链。

真实 Redis 共享额度测试需显式设置 TASKFLOW_RATE_TEST=true，以及 TASKFLOW_GATEWAY_REDIS_*，执行 .\mvnw.cmd -pl taskflow-gateway test "-Dtest=RedisRateLimitIntegrationTests"。测试只操作随机 taskflow:test 命名空间，键自动过期，不扫描或清空现有数据。

真实 Redis 会话脚本集成测试设置 TASKFLOW_REDIS_SESSION_TEST=true，使用独立 test-redis-connection + test profile，执行 .\mvnw.cmd -pl taskflow-admin test "-Dtest=RedisSessionIntegrationTests"。仅操作随机 taskflow:admin:access:test 命名空间，不清空数据库。

前端真实身份浏览器测试使用 playwright.access.config.js 和 TASKFLOW_ACCESS_SETTINGS 指向忽略的 JSON：issuer、username、password、viewerUsername、viewerPassword。测试 Keycloak 必须是专用隔离 Realm，对应账号分别为 ADMIN/VIEWER，回调允许 TASKFLOW_ACCESS_BASE_URL（默认 http://127.0.0.1:5179）。执行 npx.cmd playwright test --config playwright.access.config.js；截图写入 .tools/access-browser。仅测试源集中的 TestSessionConfiguration 可通过隔离 JVM 的 spring.main.sources 显式装入；缩短超时测试需同时设置 idle-timeout=10s、login-window=5s、TASKFLOW_ACCESS_IDLE_WAIT_MS=11000。生产 JAR 不包含此测试存储。不要将此配置或账号传给生产 Realm。

2026-10-28 验证环境的 Redis 不可达，真实 Redis 共享额度及会话 TTL 集成测试未执行；429、故障拒绝、30 分钟续期/闲置边界、刷新不得恢复超时会话及注销使用隔离测试验证。真实浏览器登录验证通过独立 Keycloak、H2 Admin 与仅位于测试源集的会话存储进行，Nginx → Gateway → 真实 Redis 的完整生产链路尚未验收。

2026-10-30 已通过同一版本的带登录 MQ 回归：Nginx → Gateway → 真实双 Redis → Admin，以及配置同步、Cron/手动触发、MQ、Worker 与结果查询同时运行，并覆盖权限、会话过期、退出和 Redis 故障恢复。业务数据库仍为隔离 H2；环境、命令和未验证范围见 [带登录回归记录](authenticated-regression-verification.md)，自动启动方式见 [运行说明](authenticated-regression.md)。

## 技术依据

- [Spring Security Servlet JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security WebFlux JWT](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html)
- [Gateway RequestRateLimiter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html)
- [Keycloak JavaScript 适配器](https://www.keycloak.org/securing-apps/javascript-adapter)
- [Keycloak 客户端与角色](https://www.keycloak.org/docs/latest/server_admin/)

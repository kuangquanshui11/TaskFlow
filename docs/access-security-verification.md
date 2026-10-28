# 登录与会话安全验证

日期：2026-10-28。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| 根目录 .\\mvnw.cmd verify | 通过 | 六个 Java 应用构建与默认测试；外部 MySQL/Redis 等 opt-in 测试保持跳过 |
| Admin 最终安全定向测试 | 10 项通过 | JWT、权限、Redis 会话准入、30 分钟滑动边界、旧登录不得重建过期会话、轮换宽限、注销、防故障放行 |
| Gateway 安全及路由测试 | 通过 | 无效 JWT 在派发/限流前拒绝；429 不调用下游；Redis 连接失败返回 503；保留原路由、请求限制、追踪和无自动重试 |
| 前端单元测试 | 12 项通过 | Bearer 头、401 清理、同源限制、POST 不重放、按账号隔离未确认 Run，以及原有表单逻辑 |
| 前端生产构建 | 通过 | Vue/Vite 与官方 keycloak-js |
| 真实 Chrome + Keycloak | 3 项通过 | 前端登录页；真实账号密码登录后调用 JWT API 并新建 Job；VIEWER 无写按钮且后端拒绝直接写；退出后旧 JWT 会话失效；闲置后请求触发登录页 |
| 最终退出交互复验 | 通过 | 退出立即隐藏控制台，随后完成会话撤销及身份服务退出 |
| 桌面及 375px 页面 | 通过 | 登录页截图、无横向溢出；无浏览器脚本异常 |
| Nginx 配置 -t | 通过 | 由正式模板生成隔离配置，含 IP limit_req 规则 |

本轮真实身份验证使用独立 Keycloak 26.8.1、随机测试账号、H2 Admin 和测试源集中的会话存储；浏览器把 idle-timeout 缩短为 10 秒验证跳转，Java 测试通过可控时钟验证精确的 30 分钟边界。测试身份和运行数据仅位于忽略的 .tools，未写业务 MySQL，也未修改实际 Keycloak 用户。测试公私钥是公开的 RSA 测试夹具，不可用于部署环境。

用户明确要求不启动不可达的 Redis，因此真实 Redis Lua/TTL 与跨 Gateway 共享额度测试未执行。已提供 RedisSessionIntegrationTests 和 RedisRateLimitIntegrationTests 供恢复 Redis 后显式运行；不把测试替身描述成真实 Redis 验收。本轮也未验收 Nginx → Gateway → 真实 Redis → Admin 的完整部署链路，未安装生产 TLS 证书，未切换正在使用的服务或 Nginx 发行包。

根目录 verify 首轮发现旧并发测试未传递用户上下文以及默认拒绝改变 404/405 的断言，已修正。真实登录联调发现 Keycloak 默认客户端 scope 必须包含 basic 才会携带 sub/auth_time，已修正 Realm 并复验。运行中的测试 JAR 曾锁住 Windows 打包目标，后续使用复制到 .tools 的独立 JAR，完整构建最终通过。

部署与协议见 [登录与入口安全](access-security.md)。

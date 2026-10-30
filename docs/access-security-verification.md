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

真实身份验证使用独立 Keycloak 26.8.1、随机测试账号、H2 Admin 和测试源集中的会话存储；浏览器把 idle-timeout 缩短为 10 秒验证跳转，Java 测试通过可控时钟验证精确的 30 分钟边界。测试身份和运行数据仅位于忽略的 .tools，未写业务 MySQL，也未修改实际 Keycloak 用户。测试公私钥是公开的 RSA 测试夹具，不可用于部署环境。

Redis 在验证环境中不可达，真实 Redis Lua/TTL 与跨 Gateway 共享额度测试未执行。RedisSessionIntegrationTests 和 RedisRateLimitIntegrationTests 可在 Redis 恢复后显式运行；测试替身仅覆盖隔离环境中的应用逻辑。Nginx → Gateway → 真实 Redis → Admin 的完整部署链路及生产 TLS 尚未验收，业务服务和 Nginx 发行包未切换。

安全回归中的并发请求携带独立身份上下文，未配置路径/方法按默认拒绝策略断言。Keycloak 默认客户端 scope 包含 basic，真实登录已验证 sub/auth_time 字段；测试进程使用复制到 .tools 的独立 JAR，避免锁住 Windows 构建目标。

部署与协议见 [登录与入口安全](access-security.md)。

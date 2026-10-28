# TaskFlow 前端与 Nginx 本地部署

## 访问地址与职责

正式使用的控制台是独立 taskflow-web：Vue 3 + Vite 构建，Nginx 托管。新发行包入口 http://localhost:8090，API 转发至 Gateway http://127.0.0.1:8088，再发现 Admin；已有运行包不会自动更新。

```text
浏览器 http://localhost:8090
  ├─ /、/jobs、/schedule、/assets/* → Nginx → 前端静态文件
  └─ /api/v1/* → Nginx → Gateway :8088 → Admin 内嵌 Tomcat
                         ├─ 任务详情 → Redis → 未命中查询 MySQL 并回填
                         └─ 分页、统计与写操作 → MySQL :3306
```

Admin 已实现任务详情 Redis 缓存、提交后失效与缓存故障降级；默认 TTL 60 秒。新增 Gateway 保持前端接口路径与 JSON，需更新 Nginx 上游并启动平台服务。缓存分支、一致性边界和服务归属见 [访问与缓存流程](request-flow.md)，开关与连接说明见 [Redis 配置](redis.md)。

Admin 原有 18080 首页保留为早期演示页面，不再作为正式控制台入口。新前端没有演示数据回退：无任务显示空状态，网络失败显示错误和重试。

## 首次构建与打包

在项目根目录执行，需 Node.js、JDK 21、可用 Admin 数据库配置；首次安装依赖与下载 Nginx 需要网络：

```powershell
.\tools\package-frontend.ps1
```

脚本完成 npm 构建、校验官方 Nginx ZIP 的固定 SHA256、生成独立发行目录，并生成：

- dist/taskflow-web.zip：构建后的 html 与 Nginx 配置，供已有 Nginx 的环境使用。
- dist/taskflow-nginx-windows.zip：含 Windows Nginx 可执行文件、静态页面、配置、管理脚本和许可证；解压后可独立运行。
- dist/SHA256SUMS.txt：两个部署包的校验值。
- dist/latest-build.txt：最新构建的相对发行路径。

每次构建生成新的 dist/releases/时间戳/taskflow-nginx，不覆盖当前运行实例；包内不包含数据库密码、本机 properties、Java JAR、node_modules 或测试数据。Nginx 固定版本 1.31.8，下载来源与原始校验值见打包脚本。

## 激活与启动

首次使用时激活最新构建，再分别启动后端和 Nginx：

```powershell
# 先准备本机平台凭据，再启动六个应用（需已完成业务库配置）
.\tools\prepare-platform.ps1
.\tools\services.ps1 -Action start
```

```powershell
# 终端二：首次激活，随后启动 Nginx
Copy-Item -LiteralPath dist/latest-build.txt -Destination dist/current-release.txt
.\tools\nginx.ps1 -Action start
.\tools\nginx.ps1 -Action status
```

浏览器打开 http://localhost:8090。Nginx 在后台运行，不需要保持启动它的终端窗口。解压 Windows 包后也可在包内运行 .\manage.ps1 -Action start；URL 与上游端口写在包内 README.txt。

## 修改代码后的更新

```powershell
.\tools\package-frontend.ps1
# 先停止旧发行实例，再切换指针，避免两个实例争用 8090
.\tools\nginx.ps1 -Action stop
Copy-Item -LiteralPath dist/latest-build.txt -Destination dist/current-release.txt
.\tools\nginx.ps1 -Action start
```

stop 使用 Nginx 的 quit 信号优雅退出；若端口尚未释放，稍等后再次 start。只修改当前发行目录 conf/nginx.conf 时，先 test 再 reload：

```powershell
.\tools\nginx.ps1 -Action test
.\tools\nginx.ps1 -Action reload
.\tools\nginx.ps1 -Action stop
```

工具只管理 current-release.txt 指向的项目实例，不调用全局 nginx，也不停止其他 Nginx 或 Java 进程。重建包不会自动切换正在运行的服务。

## 端口与配置

| 项目 | 默认本机值 | 修改方式 |
| --- | --- | --- |
| 前端 Nginx | 127.0.0.1:8090 | 打包参数 -HttpPort |
| Gateway 上游 | 127.0.0.1:8088 | 打包参数 -GatewayHost / -GatewayPort |
| MySQL | localhost:3306 | Admin 自己的数据库配置 |
| 浏览器测试 | Nginx 8091 → Gateway 19088 → Admin 19080 | tools/test-frontend.ps1；另有 Scheduler 19081 / Worker 19082 |

例如 `.\tools\package-frontend.ps1 -HttpPort 8092 -GatewayPort 9088`。生产 JS 始终请求同源 /api/v1，不硬编码服务地址。nginx.conf.template 是可维护的配置源，生成后的 conf/nginx.conf 可独立编辑。

API proxy_pass 保留 /api/v1 路径，明确连接/读取超时，禁止自动上游重试。HTML 使用 no-cache，带哈希的 assets 长期缓存；/jobs 等历史路由支持直接访问和刷新，缺失的 assets 返回 404。静态页面不需要启动 Node.js。

Windows Nginx 包用于当前本地学习环境，仅监听回环地址。部署到服务器时使用该系统原生 Nginx，按实际目录配置 root、日志与上游；身份认证和 TLS 在后续交付阶段实现。

## 验证与故障排查

```powershell
# 根目录：隔离测试，不写用户 MySQL
.\tools\test-frontend.ps1
# 根目录：六个独立 Java 应用的构建与测试
.\mvnw.cmd verify
# 实际注册/配置中心与 OTLP 导出验收
.\tools\test-platform.ps1 -SkipBuild
```

测试脚本先确认 19080/19081/19082/19088/8091 空闲，启动隔离的 Gateway 和三个业务服务，并从启动日志确认各自 H2 文件库，再运行可写的 API/浏览器测试。使用本机 Chrome，无需额外下载测试浏览器；退出时只清理本次创建的进程。

隔离多服务测试由 test-execution.ps1 生成本轮临时 YAML，连接、端口、凭据与日志保存在被忽略的 `.tools/execution-e2e/<时间戳>`；数据库和 Redis 配置不在业务 Java 中硬编码。test-platform.ps1 另启动 Eureka 19161、Config 19188 和 OTLP 测试接收器 19118。

- 页面可打开但接口失败：先检查 Gateway 8088 健康，再检查 Admin 实际端口和 Eureka 注册；核对 Nginx 上游为 Gateway。
- 启动报端口占用：检查是否已有本项目实例，不要直接结束不明进程；可重新打包使用其他 HttpPort。
- 样式或脚本 404：确认使用完整 html 目录，避免只复制 index.html。
- 日志位于当前发行目录 logs/access.log、logs/error.log；路径由 dist/current-release.txt 指示。
- 浏览器/API 测试日志位于 `.tools/execution-e2e/<时间戳>`，失败截图与 trace 位于 taskflow-web/test-results。平台证据另见 `.tools/platform-e2e`。

## 官方参考

- [Nginx Windows 使用说明](https://nginx.org/en/docs/windows.html)
- [Nginx proxy_pass 路径语义](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)
- [Nginx 官方下载](https://nginx.org/en/download.html)

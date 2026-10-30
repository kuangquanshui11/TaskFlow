# TaskFlow Web

独立 Vue 3 / Vue Router / Vite 前端，通过相对路径 /api/v1 调用 Admin，不引用 Java 应用模块。部署后由 Nginx 返回静态文件并代理 API。

## 开发

使用 Node.js 20.19+ 或 22.12+（本机已验证 Node 24），从本目录执行：

```powershell
npm.cmd ci
npm.cmd run dev
```

默认开发地址由 Vite 输出，API 代理到 Gateway 8088。需要修改上游时，在启动 Vite 前设置 TASKFLOW_GATEWAY_ORIGIN，例如 http://127.0.0.1:9088；生产包不包含该地址，生产路由由 Nginx 配置控制。

```powershell
npm.cmd run build
npm.cmd test
```

构建生成本目录 dist。完整浏览器测试从仓库根目录执行 tools/test-frontend.ps1，会启动隔离 Gateway、三个 H2 业务服务和测试 Nginx，结束后停止脚本启动且身份匹配的进程。真实 Eureka/Config/OTLP 验收使用 tools/test-platform.ps1。先运行 tools/package-frontend.ps1 准备 Nginx，机器需已安装 Chrome；改用已安装的 Edge 时设置 PLAYWRIGHT_BROWSER_CHANNEL=msedge。

## 模块

| 目录 | 职责 |
| --- | --- |
| src/api | 同源 API 封装、超时、错误协议 |
| src/composables | 控制台状态与变更协调 |
| src/lib | 表单转换、校验、日期显示 |
| src/components | 表格、抽屉、表单、状态、图标 |
| src/pages | 工作台、任务管理、时间预览、工作节点 |
| src/styles | 语义令牌与响应式布局 |
| tests | 表单/客户端单元测试与真实接口浏览器测试 |

API 字段见 docs/api.md，部署见 docs/frontend-deployment.md。页面只管理已实现的配置能力，不模拟 Scheduler、Worker 或 Redis 功能。

`/workers` 展示 Scheduler 的真实节点记录、在线状态、Handler、容量与最后心跳，支持过滤、分页和自动刷新。查询失败不伪装为空列表或继续显示在线，离开/隐藏页面停止轮询，移动端长标识转为可换行卡片。完整三服务节点浏览器验收从根目录运行 `tools/test-execution.ps1 -SkipBuild`，协议见 [节点管理](../docs/worker-nodes.md)。

所有 E2E 管理请求使用真实登录后的当前 JWT。统一脚本自动准备独立 Keycloak、测试账号和双 Redis；MQ 全链路、会话过期、退出及 Redis 故障的运行说明见 [带登录的任务链路回归](../docs/authenticated-regression.md)。

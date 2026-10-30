# 前端与 Nginx 验证记录

日期：2026-10-26。环境：Windows、Node.js 24.15.0、Chrome、Vue 3.5.44、Vite 8.3.5、Nginx 1.31.8、Java 21、Spring Boot 4.1.1。

## 功能与部署范围

- taskflow-web 独立构建，工作台、任务管理、时间预览接入真实 Admin API。
- Nginx 8090 静态托管、/api/ 转发至本机 Admin 18080，使用本机既有 MySQL 数据库。
- 生成 dist/taskflow-web.zip 和 dist/taskflow-nginx-windows.zip，提供独立发行目录及启动/停止/重载脚本。
- 任务详情规划采用 Nginx → Tomcat → Redis → MySQL 链路；2026-10-26 前端验证时尚未接入 Redis，后续实现见 [访问与缓存流程](request-flow.md)。

## 自动化验证

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| npm.cmd run build | 通过 | 44 个模块，按路由拆分构建；无外部字体、图片 CDN 依赖 |
| npm.cmd test | 6 项通过 | JSON 类型与大小、数字策略范围、空说明编辑、手动任务 Cron 清除、HTTP 错误和取消 |
| tools/test-frontend.ps1 浏览器部分 | 6 项通过 | 见下方场景；通过真实 Nginx 与 Admin API |
| 根目录 mvnw.cmd verify | 20 项通过 | Admin 16、Scheduler 2、Worker 2；三个可执行 JAR 构建成功 |
| Nginx 配置检查 | 通过 | 发行目录与解压后的 Windows 包均执行 nginx -t |
| 部署包内容检查 | 通过 | 不包含 properties、Java JAR、node_modules、测试结果等开发或私密文件 |

浏览器场景：

1. 创建、详情、JSON 错误反馈、修改、启用、停用、名称筛选与软删除。
2. 外部变更导致配置版本冲突时，显示错误并保留未保存草稿。
3. Cron 正确与错误输入、错误恢复、Nginx 历史路由刷新、静态缓存头和缺失资源 404。
4. 网络错误显示加载失败，重试后恢复，不把请求失败显示成空数据。
5. 编辑期间修改时间规则，忽略旧请求返回的预览结果。
6. 375px 导航、任务表格、编辑抽屉，无页面横向溢出，Escape 关闭窗口。

可写浏览器验证仅连接专用 Nginx 8091 → Admin 18081 → H2 内存数据库。脚本从启动日志确认 H2 后才开始浏览器写入；测试后停止测试实例。业务 MySQL 未写入测试任务。

## 本机实际访问

Nginx 8090 的 /api/v1/jobs/selectSummary 返回 200、code=OK；本机真实数据库任务数为 0，页面展示真实空状态。工作台、任务管理、时间预览在 1440、1024、768、375px 宽度下均无页面横向溢出，浏览器 pageerror 为 0。截图见 [工作台](screenshots/taskflow-overview.png)、[移动端时间预览](screenshots/taskflow-mobile.png)。

## 验证边界

当次验证任务配置管理与前端部署，使用关闭 Redis 缓存的隔离 profile；后续新增的后端详情缓存验证见 [缓存验证记录](cache-verification.md)，不将本记录当作 Redis 缓存的浏览器验收。Scheduler/Worker 的任务派发执行、登录授权及服务器 TLS 尚未实现，页面未展示模拟执行结果。当前 Windows Nginx 只监听本机回环地址。

复现命令与日志位置见 [部署说明](frontend-deployment.md)。前端测试报告位于 taskflow-web/playwright-report，Maven 构建日志位于 target/frontend-maven-verify.log。

## 2026-10-30：任务处理器中文说明

环境：Windows、Node.js 24、Vue 3、Vite 8.3.5、Chrome，实际 Nginx 控制台及 Keycloak 登录、Gateway/Admin API。此次仅修改任务编辑表单的展示文案、说明区域和对应文档，API 与任务执行行为保持原约定。

命令与结果：

- 在 `taskflow-web` 执行 `npm.cmd test`：12 项通过，零失败。
- 在 `taskflow-web` 执行 `npm.cmd run build`：构建成功，61 个模块。
- 根目录执行 `tools/package-frontend.ps1 -SkipBuild -HttpPort 5179` 并切换前端发行目录：打包成功，Nginx 配置检查与启动成功。
- 根目录执行 `node .tools/console/handler-probe.mjs`：使用本机既有账号完成真实浏览器登录，四项中文选项与说明、参数保留、375px 布局、重新打开清理旧说明及键盘选择、无页面 JavaScript 错误共 8 组检查通过。
- `git diff --check`：通过。

浏览器检查覆盖：成功处理器立即成功、失败处理器持续失败、首次失败处理器至少两次尝试的提示、耗时处理器参数范围 0–60000 毫秒、默认 1000 毫秒与 5000 毫秒示例；切换选项保留已填写的参数，移动端说明和示例无横向溢出。已查看桌面与移动端截图确认布局。

证据位于忽略目录 `.tools/console/handler-report.json`、`handler-desktop.png` 和 `handler-mobile.png`。此次浏览器检查没有创建、修改或触发任务；不作为新的业务执行或后端验收结果，未重新执行 Maven 验证。

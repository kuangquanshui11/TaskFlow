# 前端与 Nginx 验证记录

日期：2026-10-26。环境：Windows、Node.js 24.15.0、Chrome、Vue 3.5.44、Vite 8.3.5、Nginx 1.31.8、Java 21、Spring Boot 4.1.1。

## 交付结果

- taskflow-web 独立构建，工作台、任务管理、时间预览接入真实 Admin API。
- Nginx 8090 静态托管、/api/ 转发至本机 Admin 18080，使用本机既有 MySQL 数据库。
- 生成 dist/taskflow-web.zip 和 dist/taskflow-nginx-windows.zip，提供独立发行目录及启动/停止/重载脚本。
- docs/request-flow.md 记录用户确认的 Nginx → Tomcat → Redis → MySQL 目标链路，明确 Redis 尚未接入。

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

可写浏览器验证仅连接专用 Nginx 8091 → Admin 18081 → H2 内存数据库。脚本从启动日志确认 H2 后才开始浏览器写入；测试后停止测试实例。用户 MySQL 没有写入测试任务。

## 本机实际访问

Nginx 8090 的 /api/v1/jobs/selectSummary 返回 200、code=OK；本机真实数据库任务数为 0，页面展示真实空状态。工作台、任务管理、时间预览在 1440、1024、768、375px 宽度下均无页面横向溢出，浏览器 pageerror 为 0。截图见 [工作台](screenshots/taskflow-overview.png)、[移动端时间预览](screenshots/taskflow-mobile.png)。

## 验证边界

当次验证任务配置管理与前端部署，使用关闭 Redis 缓存的隔离 profile；后续新增的后端详情缓存验证见 [缓存验证记录](cache-verification.md)，不将本记录当作 Redis 缓存的浏览器验收。Scheduler/Worker 的任务派发执行、登录授权及服务器 TLS 尚未实现，页面未展示模拟执行结果。当前 Windows Nginx 只监听本机回环地址。

复现命令与日志位置见 [部署说明](frontend-deployment.md)。前端测试报告位于 taskflow-web/playwright-report，Maven 构建日志位于 target/frontend-maven-verify.log。

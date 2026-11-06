# 独立运行详情验证

日期为 2026-11-06（Asia/Shanghai）。

## 环境

Windows、Node.js 24.15.0、Java 21.0.12.1、Playwright Chrome。隔离进程使用 Nginx、Gateway、Admin、Scheduler、Worker、Keycloak、两份 Redis 和各服务的 H2 测试库。Worker 使用 HTTP 派发。

## 命令与结果

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| 在 `taskflow-web` 运行 `npm.cmd test` | 26 项通过 | `.tools/run-detail-browser.log` |
| 在 `taskflow-web` 运行 `npm.cmd run build` | 构建成功，生成独立加载的 RunDetailPage | `.tools/run-detail-browser.log` |
| 在根目录运行 `.\tools\test-execution.ps1 -SkipBuild` | 51 项隔离验收检查通过，包含 20 项控制台和 3 项登录会话浏览器测试 | `.tools/execution-e2e/20261106-135640-823/report.json`、`.tools/run-detail-browser.log` |

桌面和移动端截图位于同一隔离运行目录，文件名为 `run-detail-desktop.png` 和 `run-detail-mobile.png`。

## 验证范围

- 从全局列表进入独立详情，直接刷新同一 URL，从任务抽屉进入时关闭抽屉。
- Worker 首次尝试失败并自动重试成功后删除任务，详情仍显示原配置版本、参数和两次 Attempt；校验错误码、目标 Worker 和派发至结束的耗时。
- 在 390px 视口查看长参数，检查横向溢出与页面脚本异常。以浏览器注入的响应覆盖临时 503、重试恢复和历史 Attempt 缺少目标与时间的情况。
- 查询不存在的 UUID 返回 404；无效链接显示格式提示，可返回全局列表。

## 未验证范围

本次使用隔离进程验收。实际部署环境与 RabbitMQ 派发的浏览器链路未重新验证。

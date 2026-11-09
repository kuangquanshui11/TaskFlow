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

## 2026-11-09 受控重跑验证

环境为 Windows、Java 21.0.12.1、Node.js 24.15.0、Playwright Chrome。进程验收使用独立的 Nginx、Gateway、Admin、Scheduler、Worker、Keycloak、Redis 和 H2，任务通过 HTTP 派发。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `mvnw.cmd -B -o verify` | 380 项中 356 项通过，24 项按环境条件跳过，0 项失败或错误。覆盖同键并发、失败历史资格、首次 Attempt 写入回滚、当前配置版本、操作者绑定、只读角色拒绝和 Gateway 执行限流 | `.tools/run-rerun-root-verify-final.log` |
| `npm.cmd test` 与 `npm.cmd run build` | 28 项通过，前端生产构建成功 | `.tools/run-rerun-browser-final.log` |
| `tools/test-execution.ps1 -SkipBuild -IncludeRunRerun` | 53 项隔离脚本检查通过，包含全局运行列表、重跑和独立详情 3 项 Playwright 用例 | `.tools/run-rerun-browser-final.log`、`.tools/execution-e2e/20261109-112042-554/report.json` |

重跑用例在隔离 Scheduler 库构造一条历史执行器拒绝记录，然后通过真实 API 更新任务配置。在首次重跑已受理后，Playwright 丢弃响应，刷新页面并按同一请求键重试。验证新 Run 采用确认版本、实际执行成功、保存认证操作者与来源，而且运行总数只增加一条。375 px 下检查确认框宽度和详情布局，截图为该报告目录下的 `run-rerun-mobile.png`。

修正路由启动顺序前，完整浏览器回归在全局运行列表用例达到 90 秒超时，8 项通过、2 项按条件跳过。一次详情专项还捕获到返回链接携带 Keycloak 回调查询参数。修正后两项受影响的用例均在上述 3 项专项中通过。本轮验证范围为隔离 HTTP 和 H2，完整浏览器套件、实际部署的 MySQL 迁移和 RabbitMQ 重跑链路保留为后续验收。首轮日志为 `.tools/run-rerun-e2e-final.log` 与 `.tools/run-rerun-browser.log`。

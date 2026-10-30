# 工作台业务场景展示验证

日期：2026-10-30。环境：Windows、Node.js 24、Vue 3、Vite 8.3.5、Chrome、实际 Nginx 前端及既有 Keycloak/Gateway/Admin 登录链路。

## 展示范围

我在工作台任务统计下方展示四个业务接入示例：超时未付款订单检查、每日销售报表、定期库存同步和过期数据清理。每个场景说明业务问题、执行时间、处理动作和用途，并允许展开查看 Cron、时区、建议处理器标识、业务参数与接入要求。

这些卡片用于解释平台的适用场景，不创建 Job，不计入任务统计，不提供业务执行结果。页面明确提示真实业务尚未接入，建议处理器需要开发并注册后才能使用。实际任务列表和统计仍来自 Admin API；API 与后端执行行为没有变更。

## 验证命令与结果

| 命令 | 结果 |
| --- | --- |
| `taskflow-web` 中执行 `npm.cmd test` | 12 项通过，零失败 |
| `taskflow-web` 中执行 `npm.cmd run build` | 构建成功，63 个模块 |
| 根目录执行 `tools/package-frontend.ps1 -SkipBuild -HttpPort 5179` | 打包成功，切换前端发行目录后 Nginx 配置检查与启动成功 |
| 根目录执行 `node .tools/console/scenarios-probe.mjs` | 8 组浏览器检查通过 |
| `git diff --check` | 通过 |

浏览器使用既有账号完成真实登录，检查四张卡片的文字与配置展开、768px/375px 布局和参数换行、Enter 键展开与收起、刷新后场景仍存在，以及没有页面 JavaScript 错误或 Job/Run 写请求。为稳定截图，最终截图关闭过渡动画；已查看桌面和手机截图。

证据保存于忽略目录 `.tools/console/scenarios-report.json`、`scenarios-desktop.png` 和 `scenarios-mobile.png`。

## 验证边界

本次仅验收工作台场景说明与交互，没有创建、修改或触发任务，没有接入订单、报表、库存或清理服务，也没有重新执行 Maven 验证。业务接入后的实际执行效果需要各业务处理器完成后另行验证。

# 全局运行列表验证

日期：2026-11-05（Asia/Shanghai）。

## 环境与命令

Windows、Java 21.0.12.1、Node.js 24.15.0、Spring Boot 4.1.1；浏览器使用 Playwright 的 Chrome channel。后端测试使用各服务隔离 H2；进程验收使用独立文件库、专用端口的 Keycloak、两份独立 Redis、Gateway、Admin、Scheduler、Worker 和 Nginx，派发通道为 HTTP，不连接本机业务数据库。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| 根目录 `.\mvnw.cmd -B verify` | 七个服务模块成功；370 项中 346 通过、24 条件跳过，0 失败/错误；18:07:13 +08:00 完成 | `.tools/global-runs-verify.log`、各模块 Surefire 报告 |
| taskflow-web 中 `npm.cmd test` | 26 项全部通过，含新增 3 项查询参数测试 | `.tools/global-runs-browser.log` |
| taskflow-web 中 `npm.cmd run build` | 成功，包含独立懒加载 RunsPage | `.tools/global-runs-browser.log` |
| `.\tools\test-execution.ps1 -SkipBuild` | 51 项进程/浏览器汇总检查通过；控制台 19 项、登录权限 3 项浏览器测试全部通过 | `.tools/global-runs-browser.log`、`.tools/execution-e2e/20261105-180852-686/report.json` |

根验证的 24 个跳过项属于外部 MySQL、Redis、Broker 等条件专项，未计入通过项。进程验收另用独立 Redis 验证真实会话、限流和故障拒绝，但不替代全部被跳过的数据库或 MQ 专项。

## 全局查询专项

Scheduler 新增 6 项测试，使用真实 Service、Mapper、迁移后的隔离数据库和内部 HTTP 入口，覆盖：

- 时间、状态、任务和触发来源的交集，精确 ID 与其他条件冲突时为空。
- `[from,to)` 边界、亚毫秒输入向上取整、默认 24 小时及精确历史 ID 无缺省时间限制。
- 同一创建时间按 ID 降序、跨页无重复、空页保留总数，以及列表和 COUNT 使用相同条件。
- 非法枚举、UUID、任务 ID、缺失/非法/超长时间窗、分页边界和控制密钥；完整 7 天可查询。
- 列表不返回快照，不解析当前任务或执行参数，不改变持久化运行状态。

Admin 新增 5 项真实 HTTP/客户端测试，覆盖 VIEWER 的 run:read、匿名 401、无权限 403、参数拒绝发生在远程调用之前、参数完整传递、远程调用不持有本地 SQL 事务，以及远程 503 和损坏分页响应的错误分类。

前端新增 3 项单元测试覆盖固定相对窗口、显式历史 UUID 查询、任务 ID 字符串传输、非法输入、自定义时间到 UTC 的转换。

新增 1 项浏览器测试通过真实登录和 API 创建、执行两次成功运行，删除对应任务后仍可在全局列表中查询。验证了跨任务列表、状态/任务/手动来源交集、完整 Run ID、冲突条件空结果、自定义时间、超过 7 天的前端拒绝、Cron 来源和页大小调整。真实业务记录查询经 Nginx → Gateway → Admin → Scheduler。

查询失败与重试使用浏览器注入 503；分页控件使用受控双页响应验证上一页/下一页、行切换及翻页窗口不漂移。真实 SQL 排序、分页和总数由 Scheduler 专项验证，不将受控响应当作数据库分页验收。

桌面与 390px 浏览器检查通过，无页面横向溢出、无脚本异常；移动端运行记录转为卡片、完整 ID 可换行。截图保存在上述进程目录的 `global-runs-desktop.png` 和 `global-runs-mobile.png`，不纳入 Git。

## 未验证范围

未执行本次全局查询的真实 MySQL 执行计划、百万级历史数据负载、多 Scheduler 实例并发翻页或 MQ 故障专项；H2 与 HTTP 进程验收不替代这些结果。不同翻页请求不承诺同一数据库快照。独立运行详情页、人工重跑、告警和审计不属于该列表能力。

未重启或重新部署常驻业务实例。构建产物、测试配置、凭据、日志与截图均留在忽略目录或 target/dist，不纳入提交。

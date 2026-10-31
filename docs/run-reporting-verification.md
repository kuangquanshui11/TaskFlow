# TaskFlow 运行统计验证记录

验证日期：2026-10-31（Asia/Shanghai）。环境：Windows / PowerShell 7.6.5、Temurin JDK 21.0.12.1、Maven Wrapper 3.10.0、Spring Boot 4.1.1、H2 2.4.240（MySQL 模式）、Node.js 24.15.0、npm 11.12.1。业务测试使用 test profile 和隔离 H2，不连接本机业务 MySQL。

## 命令与实际结果

按顺序执行：

```powershell
.\mvnw.cmd -pl taskflow-scheduler -am test
.\mvnw.cmd -pl taskflow-admin -am test
.\mvnw.cmd verify
cd taskflow-web
npm.cmd test
npm.cmd run build
cd ..
.\tools\test-frontend.ps1 -SkipBuild
```

Scheduler 模块测试 117 项通过、0 跳过。Admin 模块测试共 98 项，92 项通过、6 项环境条件跳过。根 verify 首轮通过；补充亚毫秒窗口断言及查询边界修正后，重新运行完整 verify，结果 BUILD SUCCESS，六个服务分别生成可执行 JAR。最终根测试汇总：

| 模块 | 总数 | 通过 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| taskflow-registry | 1 | 1 | 0 | 0 |
| taskflow-config-server | 1 | 1 | 0 | 0 |
| taskflow-gateway | 10 | 9 | 0 | 1 |
| taskflow-admin | 98 | 92 | 0 | 6 |
| taskflow-scheduler | 117 | 117 | 0 | 0 |
| taskflow-worker | 66 | 65 | 0 | 1 |
| 合计 | 293 | 285 | 0 | 8 |

新增 RunSummaryTests 7 项、RunReportingMigrationTests 1 项、RunSummaryApiTests 6 项全部通过，无新增跳过项。统计覆盖空库、五状态计数、成功率四舍五入、Attempt 与重试数、半开窗口及亚毫秒参数、真实重试流转的首尾时间、失败分组/UNKNOWN/Top 5、10,000 个最早样本与 nearest-rank、完整窗口平均值、参数校验、默认窗口及密钥/权限。Admin 同时验证真实 SchedulerClient 的显式响应分支、模拟缺字段/503、调用不在 SQL 事务内。迁移测试从 Flyway V8 起步，验证 V9 回填终态的 MIN/MAX、保留非终态 null 及索引存在。

前端单测 23 项全部通过、0 跳过，其中新增 run-summary 4 项，覆盖 UTC 窗口计算、成功率空值/零值、耗时边界和状态分布。Vite 生产构建成功，无新增依赖或设计令牌。

最终浏览器复验成功：隔离进程脚本 51 项检查全部通过，控制台浏览器用例 18 项通过（含新增工作台 1 项），登录用例 3 项通过，0 跳过。新增用例先核对真实 Scheduler 统计、24 小时 / 7 天参数与页面数量，再显式注入 503 和空数据验证错误隔离及空窗口展示；并检查 375px 无横向溢出。截图前等待移动侧栏离开视口，桌面与移动截图均已人工查看，指标、分布条及失败列表正常显示。

## 证据

- `.tools/run-reporting-scheduler.log`：Scheduler 模块测试。
- `.tools/run-reporting-admin.log`：Admin 模块测试。
- `.tools/run-reporting-verify.log`、`.tools/run-reporting-final-verify.log`：两次根构建，最终代码以后者为准。
- 各模块 `target/surefire-reports/TEST-*.xml`：最终测试数量、错误与跳过原因。
- `.tools/run-reporting-web-test.log`、`.tools/run-reporting-web-build.log`：前端单测和构建。
- `.tools/run-reporting-browser.log`：首轮浏览器及隔离进程验收；`.tools/execution-e2e/20261031-091324-646/report.json` 记录 51 项检查。
- `.tools/run-reporting-browser-final.log`：最终复验；`.tools/execution-e2e/20261031-094555-818/report.json` 记录 51 项检查，`run-summary-desktop.png`、`run-summary-mobile.png` 为最终界面截图。

证据目录、测试数据库、日志、凭据与构建产物均不纳入 Git。

## 未验证范围与边界

- 未连接用户本机业务 MySQL；V9 已在 H2 MySQL 模式真实执行，但未在真实 MySQL 8 运行，MySQL 兼容性仍需在隔离实例复验。
- 根测试既有条件跳过：Admin 的 MySQL 迁移 1 项、Redis 连接 1 项、详情缓存 3 项、会话 1 项；Gateway Redis 限流 1 项；Worker 真实 MQ 背压 1 项，原因均为对应外部集成环境变量未提供。本次新增测试未跳过。
- 未做生产负载/查询性能测量、真实 MQ 或多实例统计一致性演练；四次读取不构成一致性快照。
- 不承诺创建到首次 RUNNING 前的初始排队被计入耗时；按指定写入点保留现有并发回调语义，缺少开始/结束时间的终态只计数量，不计耗时。历史回填与新状态写入的起点差异见设计文档。
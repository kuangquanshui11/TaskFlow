# 销售日报验证记录

## 2026-11-02 环境与结果

环境：Windows、PowerShell 7、Java 21.0.12.1、Spring Boot 4.1.1、Node.js 24.15.0、Chrome、MySQL 8.0.34。默认测试使用独立 H2；MySQL 专项新建回环地址的隔离实例，REPEATABLE READ。运行验证使用本机独立 taskflow_order 账号/schema、既有 Keycloak/Gateway/Redis/Eureka 和真实 RabbitMQ，未改已有前端回调地址。

| 命令/检查 | 结果 | 证据 |
| --- | --- | --- |
| 根目录 .\mvnw.cmd -B verify | 七个服务模块成功；359 项中335通过、24条件跳过，0失败/错误；10:15:24 +08:00 完成 | .tools/sales-final-verify.log |
| .\tools\test-order-mysql.ps1 -IncludeSales | 15项全部通过，包含7项销售日报和8项原订单测试 | .tools/order-mysql/20261102-101650-764/tests.log；.tools/sales-final-mysql-tests.log |
| taskflow-web 中 npm test | 23项全部通过 | 本轮命令输出 |
| taskflow-web 中 npm run build | 成功，包含独立懒加载销售日报页面 | 本轮命令输出 |
| .\tools\prepare-local-sales.ps1 | 创建独立 schema/随机账号与忽略配置，未接管既有非空库 | 本轮命令输出 |
| seed-sales-samples.ps1；dataset=sales-demo-v1、startDate=2026-10-03、days=30、orderCount=2000、seed=20261102 | 事务导入2000笔订单，3家店铺、30个商品 | 本机 sales_sample_batch；样例种子和命名空间见设计 |
| 浏览器真实登录、创建任务、两次人工执行、详情与 CSV 下载 | 全部通过；真实 MQ 派发，两次 Run 指向同一份日报 | .tools/console/sales-flow-evidence.json、sales-report.csv |
| 最终运行版本的桌面/390px页面、空筛选和明细控件 | 通过；移动导航正确收起，页面无横向溢出，无脚本异常 | .tools/console/sales-desktop.png、sales-mobile.png、sales-mobile-detail.png |
| 独立 JDBC 只读核对 | 2000笔样例，库存可用/预占/已消耗守恒；报表与独立 SQL 结果一致 | .tools/sales-database-check.log |
| services.ps1 -Action status | Registry、Config、Order、Scheduler、Worker、Admin、Gateway 均 UP | 最终运行检查输出 |

## 业务断言

H2 与真实 MySQL 都验证：

- 不同店铺隔离，付款时间日初包含、次日日初排除，待付款不计入。
- 固定金额两笔订单合计30000分；CSV金额、商品明细及汇总一致，公式前缀字符串转义。
- 同请求、不同 Run、12个并发生成请求共用一个范围快照，不累加金额。
- 默认日期持久化后将可控时钟推进两天，重复请求仍返回原日报；当天及未来业务日拒绝生成。
- Europe/Berlin 夏令时结束日按25小时日历区间处理。
- 明细写入故障回滚报表头，保留请求日期绑定；重试可完成。成交快照故障回滚订单及库存。
- 样例批次同参数重放不新增订单，改参数返回冲突；订单终态与预占终态对应。

Worker 新增3项测试覆盖稳定Run操作键、参数、执行预算、响应摘要和远程错误重试分类；复用已验证的订单HTTP传输，禁止POST隐式重放。Admin 新增2项真实HTTP入口测试验证VIEWER可查询/导出、无角色403、非法参数400、远程故障或损坏响应503，远程查询不持有本地SQL事务。订单HTTP测试验证生成接口使用内部令牌、业务令牌不能越权，以及样例入口默认关闭。

## 本机运行证据

浏览器入口沿用 http://127.0.0.1:5179/，未修改Keycloak回调配置。新建停用任务“销售日报（样例店铺8）”，jobId=2，handlerKey=salesReport，参数shopId=8。手动运行：

- f6661e89-0184-4a32-8a69-7c9f57e3be3a：SUCCEEDED。
- 152db00d-2c80-4193-857b-68997b847de3：SUCCEEDED。

两次返回日报6043072f-59c2-4eaf-97b3-d2e9a518b9c4。统计日期2026-11-01、Asia/Shanghai，18笔付款订单、61件商品、1927976分；CSV合计和数据库独立聚合一致。最终Order和Worker已重新加载本轮verify产物，页面查询/下载控件复查通过。任务保持停用，未开启持续自动生成。

## 验证边界

24个默认跳过项属于外部MySQL、Redis或Broker等条件专项；不能计为通过。本轮另行执行15项真实MySQL测试，既有Redis会话和MQ用于正常登录/派发链路，不代表重新完成Redis故障、多数派丢失、Broker停机或生产HA验收。

未执行销售专项的跨机器断网、真实响应丢失代理和进程强杀；持久化幂等、失败回滚、同参数重放及错误分类分别经过上述测试。未接入真实支付、退款、迟到数据覆盖重算、逐店铺权限、多币种或大规模性能负载。日报为首次生成快照，不自动纳入后补订单。

本机凭据、数据库、运行JAR、浏览器脚本和截图位于忽略的config/.tools，均不纳入Git；部署和恢复规则见 [销售日报](sales-reports.md)。

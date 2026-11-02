# 销售日报与样例订单

## 服务归属与数据来源

我将销售日报放在独立订单服务的 report 业务包，复用本服务的订单事实，不新增平台业务数据库。Admin 只代理已鉴权的查询；Scheduler 继续拥有 Run/Attempt；Worker 注册 salesReport 后通过版本化 HTTP 请求生成日报，不读取订单表。

V2 迁移新增店铺、商品目录、订单成交快照、请求日期绑定、日报头/商品明细及样例批次。已有订单及库存表保持原协议。仅具有 sales_product 目录项的 SKU 下单时在本地事务中写入 order_sale_snapshot，保存店铺、商品名称、成交单价及金额。库存、订单或快照失败整笔回滚。没有成交快照的历史订单不计入销售日报，不为缺失价格编造金额。

金额以人民币整数分表示；当前只统计单 SKU 订单，不含优惠、退款、多币种和真实资金结算。商品目录当前由受保护的样例导入建立，不提供商品改价管理页面。

## 日报与日期

任务参数为 `{"shopId":8}`，或补传 `businessDate`（ISO 日期）回补指定的已结束日期；时区取任务配置快照。订单使用付款时间划分日期，范围为该时区日初（含）到次日日初（不含），使用日历日期处理夏令时，不能固定加 24 小时。

未指定日期时，订单服务在首次成功持久化请求绑定时选择自身当前时刻在任务时区的前一天。日期和原始参数绑定到稳定的 Run 操作键，后续 Attempt 重用。该规则不等于 Cron 原计划日：积压跨天、第一次请求未到达服务就失败时，以第一次成功绑定的日期为准；历史补算应显式传 businessDate。

日报按 shopId/businessDate/timeZone 唯一。生成先锁本店铺行，再用单条聚合 SQL 读取 PAID 且具成交快照的订单，保存头和全部商品明细后一起提交。失败回滚日报，日期绑定保留；同一操作键改变参数返回 409。不同 Run 生成相同范围也返回同一份日报，不重复累计。无销售时生成零汇总、空明细。

日报是首次生成时的数据快照，生成后的迟到付款或后补样例不会自动修改已保存日报；第一版不提供覆盖重算。店铺锁仅串行化日报/样例导入，不锁住普通付款；统计以聚合 SQL 的数据库读视图为准，不声称跨服务、跨语句原子快照。

CSV 下载包含商品明细与合计，金额列单位为分，UTF-8 BOM，字符串加引号并转义，引发电子表格公式解释的前缀加单引号。浏览器仅持有自己的 JWT，订单内部令牌不会返回浏览器。

## 样例导入

我用固定 Random seed 生成 3 家店铺（8、9、10）、每家 10 个 SKU 和指定数量订单。数量、价格、历史时间及 PAID/PENDING/CLOSED 分布随机生成；同一 seed 和输入可复现业务分布，订单 UUID 不要求一致。商品 SKU 使用样例批次命名空间。

样例调用现有创建、预占、模拟付款或释放库存流程，在同一本地事务写入并调整历史订单/流水时间。PAID 对应 CONSUMED，CLOSED 对应 RELEASED，PENDING 保留 HELD。该能力仅用于本机示例，入口由 order.samples-enabled 控制，默认关闭，要求业务 API 令牌；不能经公共 Gateway 调用。

批次标识与参数一同持久化，相同批次同参数返回原结果，参数改变返回 409；出错整批回滚，不清表、不覆盖已有订单。再次执行须保留原 startDate；脚本默认日期会随日历变化。导入历史 PENDING 后，已接通的超时关单任务可以正常关闭它们。样例导入应先于首次生成该日期日报。

## 本机准备与启动

前置条件为 README 所述 Java 21、MySQL、身份/注册/配置中心、Redis 和当前 MQ。订单服务独立 schema/账号；实际凭据只存忽略的 YAML 或环境变量。

```powershell
.\mvnw.cmd verify
.\tools\prepare-local-sales.ps1
.\tools\services.ps1 -Action start -Service order
.\tools\services.ps1 -Action restart -Service admin
.\tools\services.ps1 -Action restart -Service worker
```

准备工具只接受已有的本机 Admin 管理连接，创建独立 taskflow_order 和随机专属账号；拒绝接管无本机配置的非空订单库。已有两份完整配置原样保留，部分配置要求人工核对。新增 config/taskflow-order-local.yml 和 config/taskflow-sales-local.yml 均忽略；示例关闭订单库存缓存，订单 MySQL 仍为权威来源。配置默认重启生效。

非本机自动准备环境手工创建隔离 schema/账号：订单服务配置 order.api-token、order.worker-token；Admin taskflow.sales.token 与 Worker taskflow.order.token 使用 worker-token，Worker taskflow.sales.enabled=true。Admin taskflow.sales.url 和 Worker taskflow.order.url 在本地为显式地址，cloud 留空按 taskflow-order-service 发现。超时由 YAML 配置，POST 不隐式重放，408/429/5xx/传输失败交给有限 Attempt 重试；配置、参数、权限和不存在的店铺属于不可重试错误。

用环境变量提供业务 API 令牌后执行样例脚本。OrderServiceUrl 是实际订单地址，下面为本机示例；StartDate 固定保存，Days 覆盖的日期应已结束：

```powershell
.\tools\seed-sales-samples.ps1 -OrderServiceUrl http://127.0.0.1:8083 -Dataset sales-demo-v1 -StartDate 2026-10-03 -Days 30 -OrderCount 2000
```

令牌由 TASKFLOW_ORDER_API_TOKEN 或 -Token 提供，不写日志。首次导入可能耗时，脚本等待上限 600 秒，不自动重放。响应丢失可用相同参数重试。初始化失败查看日志和数据库权限，禁止跳过迁移或清库。

控制台沿用已有入口，在“销售日报”点击“创建报表任务”，默认店铺 8、每日 01:00、保存为停用。先手动执行，Run 成功后刷新日报，查看明细和下载 CSV。启用定时计划前确认 Worker 节点具有 salesReport 能力；Admin 允许列表是配置白名单，不等同于在线执行能力。其它尚未实现的业务模板仍保持待接入。

前端构建和 Nginx 发行包更新按 [前端部署](frontend-deployment.md) 执行，Java verify 不会更新已有静态发行包。已有 Vite 开发入口继续使用原地址并读取源码，不需要修改 Keycloak 回调地址。

## 验证命令

```powershell
.\mvnw.cmd verify
.\tools\test-order-mysql.ps1 -IncludeSales
cd taskflow-web
npm test
npm run build
```

默认 H2 测试与真实 MySQL 专项分别验收。MySQL 专项创建全新隔离实例，不读写用户业务库；报告包含并发去重、快照失败回滚、日期边界、CSV 以及重复导入约束。验证证据及边界见 sales-reports-verification.md。

# 销售日报与样例订单

## 服务归属与数据来源

我将销售日报放在独立订单服务的 report 业务包，复用本服务的订单事实。Admin 代理已鉴权的查询，Scheduler 拥有 Run/Attempt，Worker 注册 salesReport 后通过版本化 HTTP 请求生成日报。

V2 迁移新增店铺、商品目录、订单成交快照、请求日期绑定、日报头/商品明细及样例批次。已有订单及库存表保持原协议。仅具有 sales_product 目录项的 SKU 下单时在本地事务中写入 order_sale_snapshot，保存店铺、商品名称、成交单价及金额。库存、订单或快照失败整笔回滚。没有成交快照的历史订单不计入销售日报，不为缺失价格编造金额。

金额以人民币整数分表示，口径为状态 `PAID` 的单 SKU 成交金额。已验签的微信正常付款进入该口径，超时关单后收到的微信款项进入退款补偿链路，订单仍为 `CLOSED`。折扣、已成交后的退款、多币种和逐店铺读权限需要各自的业务契约。目前 Admin 以 `run:read` 授权所有店铺的日报查询和下载。

## 日报与日期

任务参数为 `{"shopId":8}`，或补传 `businessDate`（ISO 日期）回补指定的已结束日期；时区取任务配置快照。订单使用付款时间划分日期，范围为该时区日初（含）到次日日初（不含），使用日历日期处理夏令时，不能固定加 24 小时。

我保留首次受理规则。未指定日期时，订单服务在首次成功持久化请求绑定时选择任务时区的前一天，后续 Attempt 复用同一 Run 绑定。比如 11 月 9 日 01:00 的任务积压到 11 月 10 日才首次成功受理，默认统计 11 月 9 日。要补原计划对应的 11 月 8 日，显式传 `"businessDate":"2026-11-08"`。

我以 `shopId/businessDate/timeZone` 标识报表范围，版本从 1 递增。生成和修订先锁本店铺行，再用一条 SQL 聚合已付款且具成交快照的订单。同一 SKU 的多个成交名称归入一行，显示付款时间最后的名称，同时间按订单 ID 排序。版本头、商品明细和请求结果一起提交。失败回滚新版本，保留日期绑定。无销售时生成零汇总和空明细。

首次生成之后，新的生成请求返回当前最新版。已成功的操作键始终返回其原版本。修订要求新的操作键、原范围的显式日期和原因，同一键改变参数或动作返回 409。普通付款可以与报表并发，报表以聚合 SQL 的读视图为准。V6 将已有日报保留为第 1 版，并保留原 ID、明细和原请求对应的版本。

默认列表每个范围显示最新版。通过 `selectRevisions` 可分页查询任一版本所在范围的修订记录，再用各版本的 ID 查询明细或下载 CSV。CSV 包含商品明细、合计及版本号，金额列单位为分，使用 UTF-8 BOM。字符串加引号并转义，会被电子表格解释为公式的前缀加单引号。

## 真实目录与历史成交补录

我将生产目录的来源定义为业务方的商品主数据系统，例如 ERP 或 PIM。发布方使用订单服务的 `api-token`，先调用 `POST /internal/v1/sales-inputs/shops/insert` 注册店铺，再调用 `POST /internal/v1/sales-inputs/products/update` 发布 SKU、店铺、名称、人民币单价、稳定的来源标识和单调递增版本。相同版本和内容可以重放，过期版本、同版本异内容、SKU 换店铺或换来源返回 409。目录版本影响之后创建订单的快照，库存仍走独立库存入口。实际发布方需配置目录系统、标识映射和调用凭据。

对本服务已有的 `PAID` 订单，从历史 POS 成交记录或支付对账凭据取得成交商品名和单位成交价，使用 `POST /internal/v1/sales-inputs/snapshots/insert` 补齐缺失快照，并登记来源系统和唯一凭据引用。补录沿用订单已有的 SKU、数量和付款时间，相同内容可重放。已存在的快照、缺失订单、未付款订单及店铺冲突分别按 API 契约处理。缺少原订单或原始成交证据的记录需先在业务侧对账。

## 迟到数据处理

1. 对照成交凭据核对店铺、原付款时间和统计时区。补齐缺失成交快照，确认补录成功。
2. 查询受影响日期是否已有日报。首次生成通过 Worker 任务显式传 `businessDate`。已有日报时，向订单服务调用 `POST /internal/v1/sales-reports/update`，提交新的 `requestKey`、原日报的 `shopId`、`businessDate`、`timeZone` 和 `reason`。原因会展示给日报读者，可记录工单号和变更说明。
3. 查询修订历史，核对新旧金额、订单数、明细和 CSV，并记录新版本 ID。一次补录影响多个时区的日报时，对每个范围分别重算。
4. 请求超时或响应丢失时使用原请求键和原参数重试。后续有新数据时使用新的请求键发布下一版。

写入口仅在订单服务可用。字段、令牌和返回码见 [销售日报 API](api.md#销售日报-apiv1)。

## 样例导入

我用固定 Random seed 生成 3 家店铺（8、9、10）、每家 10 个 SKU 和指定数量订单。数量、价格、历史时间及 PAID/PENDING/CLOSED 分布随机生成；同一 seed 和输入可复现业务分布，订单 UUID 不要求一致。商品 SKU 使用样例批次命名空间。

样例调用现有创建、预占、模拟付款或释放库存流程，在同一本地事务写入并调整历史订单/流水时间。PAID 对应 CONSUMED，CLOSED 对应 RELEASED，PENDING 保留 HELD。该能力仅用于本机示例，入口由 order.samples-enabled 控制，默认关闭，要求业务 API 令牌；不能经公共 Gateway 调用。

批次标识与参数一同持久化，相同批次同参数返回原结果，参数改变返回 409；出错整批回滚，不清表、不覆盖已有订单。再次执行须保留原 startDate；脚本默认日期会随日历变化。导入历史 PENDING 后，已接通的超时关单任务可以正常关闭它们。样例导入应先于首次生成该日期日报。

## 本机准备与启动

前置条件为 README 所述 Java 21、MySQL、身份/注册/配置中心、Redis 和当前 MQ。订单服务独立 schema/账号；实际凭据只存忽略的 YAML 或环境变量。

升级已有订单库时先停止旧订单服务并备份订单 schema，再启动新版以应用 Flyway V6。启动后抽查旧报表 ID 的明细和导出，并核对版本为 1。迁移失败时检查 Flyway 和数据库日志，核对备份后再恢复服务。

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

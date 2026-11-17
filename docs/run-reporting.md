# TaskFlow 运行统计设计

我将运行统计放在 Scheduler，由 Admin 在 `run:read` 权限下代理只读 HTTP 查询。Run/Attempt 是 Scheduler 的权威数据，Admin 不读取它的表，也不缓存统计结果。内部接口使用已有控制密钥与超时策略，公共接口沿用 ApiResponse。

我在 Run 上保存 started_at 和 finished_at，避免每次统计临时从 Attempt 推断跨重试的时间范围。V9 对历史终态按 Attempt 最早开始、最晚结束回填。后续由 RunDataMapper.runState 写入，进入 RUNNING 时记录首次开始时间，进入 SUCCEEDED、FAILED 或 CANCELLED 时记录结束时间，COALESCE 保留第一次时间。

## 统计语义

- 时间窗按 `run_execution.created_at` 过滤，`from` 含、`to` 不含，均为带时区的 UTC ISO-8601（以 `Z` 结尾）。
- `from`、`to` 要么都省略（默认最近 24 小时），要么都提供；只给一个为 400。
- 窗口必须 `from < to` 且跨度 ≤ 7 天，否则 400 / `INVALID_ARGUMENT`。
- Run 状态为 `READY / RUNNING / RETRY_WAIT / CANCEL_REQUESTED / SUCCEEDED / FAILED / CANCELLED`。
- `finished = SUCCEEDED + FAILED + CANCELLED`。成功率按成功数除以成功和失败之和，四舍五入至 4 位小数，分母为零时返回 `null`。耗时统计使用成功与失败的 Run。
- Run 耗时 = `run_execution.finished_at - started_at`，**包含排队、派发与重试间隔，不等于 Handler 执行时间**。
- `p95` 用 nearest-rank。为控制查询量，只取窗口内按 `created_at, id` 升序的前 10,000 个已完成 Run 作为样本；`sampled` 返回实际样本数，`truncated` 表示样本已截断（此时 p95 为近似值，文档必须写明）。
- 失败列表按 `(jobId, resultCode)` 聚合，`resultCode` 为空时记作 `UNKNOWN`，按 `runCount` 倒序，最多 5 条。
- 统计由同一 Scheduler 数据库上的多次读取组成，**不是一致性快照**；`observedAt` 只表示观察时刻。

我分别读取状态和完整窗口平均耗时、Attempt 总量与重试 Run 数、两个口径的耗时样本，以及失败分组。attemptTotal 统计窗口内 Run 的所有 Attempt，retriedRunCount 为存在 attempt_number > 1 的不同 Run 数；不按 Attempt 自身时间过滤。stateCounts 始终补齐七个状态键。失败组同数量时按 jobId、resultCode 排序，lastFailedAt 取组内 Run 的最大 finished_at，null 和空字符串均归 UNKNOWN。

avgMillis 使用整个窗口内具有开始及结束时间的终态 Run 算术平均，四舍五入到整数毫秒；p95Millis 使用同样有效的耗时样本，按 nearest-rank 取值。没有可用耗时样本时平均和 P95 均为 null，sampled 为 0。采样超过 10,000 时只保留最早样本，不作随机抽样。有效耗时记录总数超过上限时 truncated=true。由于读取间状态可能变化，计数和样本数可能短暂不一致。

数据库以毫秒存储时间。对亚毫秒窗口参数，我将查询边界向上取整至毫秒，以保持 from 包含、to 排除的数学语义，不使用写入路径的向下截断方式。

## 时间边界

我保留现有状态转换语义：RUNNING 在派发确认后写入。因此上述耗时描述中的排队和派发只能涵盖首次 RUNNING 之后发生的等待、后续派发及重试间隔，不涵盖创建到首次 RUNNING 前的初始排队。历史回填则以首次 Attempt 派发时间为起点，两者存在边界差异。快速结果可能在派发确认前将 Run 终结，started_at 此时仍为 null；此类 Run 计入结果数量，但不进入耗时统计。我不为补齐统计而改变派发/回调竞争语义，也不将该耗时当作 Handler 精确执行时间。

## 接口与交付边界

`GET /internal/v1/runs/selectSummary` 返回裸 Overview JSON；`GET /api/v1/runs/selectSummary` 返回 ApiResponse，data 为相同结构。两端验证时间窗，不在 SQL 事务中调用远程服务。Admin 对 total、stateCounts、latency、failures 做显式协议校验，格式异常返回 503 / SCHEDULER_PROTOCOL_ERROR，不可达返回 503 / SCHEDULER_UNAVAILABLE。只有 run:read 可以访问公共接口。

我将本阶段限定为工作台的 24 小时和 7 天运行结果、状态分布与失败 Top 5。失败组显示 jobId，不跨服务补全任务名。页面有独立加载、空数据、失败及重试提示，失败不影响任务配置区，窗口切换和离开时取消旧请求；通过刷新数据按钮刷新，不轮询。时间显示复用 Asia/Shanghai 日期助手。

我在失败数卡片提供「查看失败运行」，沿用统计返回的 UTC 起止时间并筛选 `FAILED`。失败 Top 5 的「查看运行」再叠加该组的任务 ID 和结果码。列表和统计使用同一结果码归并规则，空值归入 `UNKNOWN`。列表按当前运行状态查询，统计生成后仍可能有运行进入终态，因此列表数量可能变化。可从列表打开独立运行详情，并带着筛选条件返回。见 [全局运行查询](global-run-query.md)。

我暂不增加 P50、分钟时间序列、图表库、导出、告警、积压指标、结构化日志或容器交付，以便先验证结果口径及服务边界。V9 使用标准 ALTER、相关聚合子查询与索引语法，目标为 H2 MySQL 模式及 MySQL 8；本次真实执行环境的覆盖情况见验证记录，不连接本机业务 MySQL。

## 阶段耗时

我在 Run 详情提供 `timing`。端到端从创建到进入最终状态，Cron 计划延迟从计划触发到创建，首次排队从创建到首个 Attempt 首次派发。总览的 `endToEndLatency` 使用端到端口径，计入派发确认前已经终结的 Run。旧 `latency` 保留首次进入 RUNNING 到进入终态的定义。平均值、P95 采样上限和查询时间窗沿用上述规则。

我在 Attempt 时间线提供 `dispatchMillis`，从首次派发到首次成功领取执行租约，两个时间点均由 Scheduler 记录。Worker 在调用 Handler 的边界使用单调时钟记录 `workerTiming.handlerMillis`，从本地结果持久化到 Scheduler 确认上报记录 `workerTiming.resultReportMillis`。后者包含待上报和重试间隔。缺少端点、迟到拒绝、结果未确认或时钟倒退时，相应阶段耗时为空。重启恢复的未知执行没有 Handler 耗时，历史 Run 有有效终态时间时仍可参与端到端统计。

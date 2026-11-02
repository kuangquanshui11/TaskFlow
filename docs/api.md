# TaskFlow 业务功能与后端接口

## 用户身份与权限

除 GET /api/v1/access/selectConfiguration 和健康探针外，业务接口均要求 Authorization: Bearer <JWT>。登录在独立 Keycloak 完成；POST /api/v1/access/insert 建立或恢复有效会话，POST /api/v1/access/update 携带新 JWT 和 JSON previousToken 轮换，POST /api/v1/access/disable 撤销会话。Admin 从 Redis 核对缓存 JWT 并滑动续期 30 分钟，闲置过期返回 401，刷新令牌不能重新创建超时会话。GET /api/v1/access/selectCurrent 返回 subject、username、roles、permissions。

VIEWER 可查询及 POST 预览；OPERATOR 另可启停、触发 Run、重试同步以及发起配置对账/受控修复；ADMIN 另可新增、修改、删除 Job。Gateway/Admin 各自验签，Admin 按精确动作路径和关键 Service 方法授权。未允许的路径/方法默认 403，可能先于原 404/405 被安全层拒绝。401、403、429、503 的错误格式、完整权限与限流策略见 [登录与入口安全](access-security.md)。

安全与任务能力通过同一套 [带登录回归](authenticated-regression.md) 验证：创建/同步、Cron 与手动 Run、MQ、Worker 结果查询均保留 JWT 和真实 Redis 会话，另检查 401、403 及 Redis 故障 503 不产生 Run。

## 功能归属与实现范围

| 业务功能 | 归属服务 | 当前实现 |
| --- | --- | --- |
| 任务配置新增、查询、修改、启停、删除 | Admin | 已实现，MyBatis-Plus 持久化 |
| 任务数量统计 | Admin | 已实现，只统计任务配置，不混入运行次数 |
| 任务详情 Redis 缓存 | Admin | 已实现，默认 TTL 60 秒、提交后失效、故障回退数据库 |
| 配置对账与受控修复 | Admin 编排，Scheduler 提供当前副本 | 已实现，批量版本/墓碑检查、幂等限速补发及持久化记录；见 [协议](config-reconciliation.md) |
| Cron 时间预览 | Admin | 已实现，只计算，不创建 Run |
| 可选处理器查询 | Admin | 已实现，返回配置白名单，不代表 Worker 已在线注册 |
| 手动触发、Run/Attempt 查询 | Scheduler；Admin 代理 | 已实现，独立状态库、幂等键、完整快照 |
| Cron 到期扫描、计划去重、错过补偿 | Scheduler | 已实现，Run/首次 Attempt/计划推进同事务，SKIP/FIRE_ONCE |
| 待派发扫描、有限重试、租约校验 | Scheduler | 已实现；按能力/负载选择 MQ 节点，租约失效后按上限新增 Attempt 重新选节点 |
| 事务派发 Outbox、RabbitMQ 发布确认 | Scheduler | 已实现；三节点真实 MQ 闭环与满载背压验收通过，见可靠派发与验证记录 |
| MQ 消费确认、Inbox 去重、死信处理 | Worker | 已实现；提交后 ACK，满载等待且不消耗故障预算，过期投递 ACK 并由 Scheduler 超时收尾；实际故障有限重试；每个 Worker 独立队列，原 Attempt 重发目标不变 |
| Handler 执行、幂等接收、结果重报 | Worker | 已实现，白名单 Handler、有界执行、独立持久化 |
| Worker 注册、心跳、会话隔离和在线查询 | Scheduler 权威，Worker 上报，Admin 代理 | 已实现；MQ 动态选择、固定 Attempt 目标和路由，管理台 /workers 接入 |
| 运行统计 | Admin 代理 Scheduler | 已实现 |
| 失败告警 | 对应业务服务 | 后续阶段 |

独立 Vue 管理台已接入任务管理接口，Admin 原首页仍为演示页。尚未实现的运行能力不会返回模拟成功。

## 路径和响应约定

我们在路径中显式标注业务模块和动作，便于识别接口用途；查询使用 GET，写操作使用 POST。公共 API 通过 Nginx 8090 或 Gateway 8088 访问；Admin 自身默认 8080，本机 YAML 为 18080，仅用于内部或开发直连。所有 POST 请求使用 Content-Type: application/json。

Gateway 保留以下 API 路径，只路由 `/api/v1/**`；`/internal/v1/**`、注册发现、配置查询和非健康 Actuator 不对浏览器开放。入口限制请求体 128KB，生成/校验 `X-Request-Id`，有效时返回 `X-Trace-Id`；下游不可用或熔断返回 HTTP 503、`GATEWAY_UNAVAILABLE`，不会自动重放 POST。详细边界见 [微服务技术栈](microservice-platform.md)。

| 方法 | 完整路径 | 用途 |
| --- | --- | --- |
| POST | /api/v1/jobs/insert | 创建任务 |
| GET | /api/v1/jobs/select | 分页、组合条件查询 |
| GET | /api/v1/jobs/selectById?id=1 | 查询详情 |
| POST | /api/v1/jobs/update | 按版本替换任务配置 |
| POST | /api/v1/jobs/enable | 启用定时配置 |
| POST | /api/v1/jobs/disable | 停用定时配置 |
| POST | /api/v1/jobs/delete | 软删除任务 |
| GET | /api/v1/jobs/selectSummary | 查询未删除任务的数量统计 |
| POST | /api/v1/jobs/preview | 预览 Cron 的后续时间 |
| GET | /api/v1/job-handlers/select | 查询配置允许的处理器标识 |
| POST | /api/v1/config-reconciliation/insert | 对账一批配置；JSON requestId(UUID)、afterJobId(>=0)、batchSize(1..100) |
| GET | /api/v1/config-reconciliation/selectById?id=UUID | 查询已保存的批次和差异明细 |
| POST | /api/v1/config-reconciliation/updateRepair | 受控补发；JSON requestId(UUID)、checkId(UUID) |
| GET | /api/v1/config-reconciliation/selectRepairById?id=UUID | 查询修复结果及操作者记录 |
| GET | /api/v1/runs/selectSummary | run:read；from/to 同时省略或提供 UTC 时间窗，最长 7 天 |
| POST | /api/v1/runs/insert | 使用配置快照创建手动 Run |
| GET | /api/v1/runs/select?jobId=1&page=1&pageSize=10 | 查询 Job 的运行历史 |
| GET | /api/v1/runs/selectById?id=UUID | 运行详情、快照与 Attempt 历史 |
| GET | /api/v1/runs/selectByRequestKey?requestKey=UUID | 按原提交标识确认接收结果 |

成功响应：

```json
{
  "code": "OK",
  "message": "操作成功",
  "data": {},
  "requestId": "请求追踪标识"
}
```

创建返回 HTTP 201，其余成功操作返回 200。响应头 X-Request-Id 与正文 requestId 一致；可提供由字母、数字、点、下划线或短横线构成、不超过 64 字符的 X-Request-Id，否则由服务生成。

错误使用对应 HTTP 状态，不以 HTTP 200 伪装失败：

| HTTP 状态 | code | 情况 |
| --- | --- | --- |
| 400 | INVALID_ARGUMENT / INVALID_REQUEST | 字段、JSON、枚举、Cron、时区或查询参数错误 |
| 404 | JOB_NOT_FOUND / ENDPOINT_NOT_FOUND | 任务不存在、已删除，或接口不存在 |
| 409 | JOB_VERSION_CONFLICT | configVersion 不是最新版本 |
| 405 / 415 | INVALID_REQUEST | 请求方法或 Content-Type 不正确 |
| 503 | DATABASE_UNAVAILABLE | 数据库连接或暂时性数据库错误 |
| 500 | INTERNAL_ERROR | 未预期错误；通过 requestId 排查 |

字段校验失败时 data 为字段名到错误说明的映射；不返回 SQL、连接凭证或异常堆栈。

## 任务详情缓存语义

GET /api/v1/jobs/selectById 的路径、参数、响应字段与状态码保持不变。默认先读 Redis，未命中查 Admin 数据库并回填；缓存命中时无需连接数据库。不存在或已删除的任务在数据库返回 404，不缓存空值。分页 /select 和统计 /selectSummary 不使用详情缓存。

TTL 默认 60 秒，从数据库查询开始计时，缓存内容还带有绝对截止时间。修改、启停和删除通过数据库版本校验，返回数据库中的新值；成功提交后删除缓存，回滚和版本冲突不触发失效。读、回填或删除缓存失败不新增业务错误码；需要查库而数据库不可用时仍返回 503 / DATABASE_UNAVAILABLE。

失效失败或并发旧查询在失效后回填时，详情可能短暂返回旧版本，甚至刚删除任务的旧详情。删除最多尝试三次，耗尽后记录错误并依靠 TTL 淘汰；不提供强一致性。默认配置下旧缓存有效期不超过原查询开始后 60 秒（多实例需要时钟同步；不限制已经在途请求的总耗时）。前端收到版本冲突后重新获取详情仍可能在此窗口读到旧版本，应等待过期后重试。开关与参数见 [Redis 缓存配置](redis.md)。

## 创建任务

POST /api/v1/jobs/insert：

```json
{
  "name": "订单超时检查",
  "description": "每五分钟检查超时未付款订单",
  "handlerKey": "demoSuccess",
  "payload": {
    "batchSize": 100
  },
  "scheduleType": "CRON",
  "cronExpression": "0 */5 * * * *",
  "timeZone": "Asia/Shanghai",
  "enabled": false,
  "timeoutSeconds": 30,
  "maxAttempts": 3,
  "retryIntervalSeconds": 5,
  "misfirePolicy": "SKIP"
}
```

name 和 handlerKey 必填。其他配置默认如下：

| 字段 | 默认值与校验 |
| --- | --- |
| name | 去首尾空格后非空，最多 100 字符 |
| description | 可空，最多 500 字符 |
| handlerKey | 以字母开头，最多 100 字符；其余为字母、数字、下划线、点、短横线；区分大小写且必须在配置白名单中 |
| payload | 可空，填写时必须是 JSON 对象，序列化后不超过 64 KiB |
| scheduleType | MANUAL；可选 MANUAL、CRON |
| cronExpression | MANUAL 时必须为空；CRON 时必填，使用包含秒的六字段 Spring Cron |
| timeZone | Asia/Shanghai；必须是有效 IANA 时区，也支持 UTC |
| enabled | false；表示定时配置启停，不禁止未来的手动触发 |
| timeoutSeconds | 30；范围 1–86400 |
| maxAttempts | 3；范围 1–10，包含首次执行 |
| retryIntervalSeconds | 5；范围 1–86400 |
| misfirePolicy | SKIP；可选 SKIP、FIRE_ONCE |

创建结果 data 为完整任务配置，另含 id、configVersion、createdAt、updatedAt。日期用 UTC ISO-8601 格式，尾部为 Z。JSON 参数以对象返回，不返回数据库内的 JSON 字符串。

仅手动任务的最小请求：

```json
{
  "name": "手动演示任务",
  "handlerKey": "demoSuccess"
}
```

创建请求当前没有请求幂等键，重复提交会创建不同 Job；修改操作则必须携带配置版本。

## 查询列表和详情

```http
GET /api/v1/jobs/select?page=1&pageSize=20
GET /api/v1/jobs/select?page=1&pageSize=20&keyword=订单&enabled=false&handlerKey=demoSuccess
GET /api/v1/jobs/selectById?id=1
```

page 默认 1，范围 1–1000000；pageSize 默认 20，范围 1–100。keyword 对名称进行包含查询，百分号和下划线作为普通字符处理；handlerKey 为精确匹配。按 id 倒序分页。

列表 data 格式：

```json
{
  "records": [],
  "total": 0,
  "page": 1,
  "pageSize": 20
}
```

## 修改、启停与删除

POST /api/v1/jobs/update：

```json
{
  "id": 1,
  "configVersion": 1,
  "job": {
    "name": "修改后的订单检查",
    "handlerKey": "demoSuccess",
    "payload": {"batchSize": 200},
    "scheduleType": "CRON",
    "cronExpression": "0 */10 * * * *",
    "timeZone": "Asia/Shanghai",
    "enabled": false,
    "timeoutSeconds": 30,
    "maxAttempts": 3,
    "retryIntervalSeconds": 5,
    "misfirePolicy": "SKIP"
  }
}
```

update 是整份配置替换；job 内省略的可选字段恢复创建时的默认值，不是局部 PATCH。编辑页面应先查询完整配置，修改后再提交。

enable、disable、delete 使用相同请求结构：

```json
{
  "id": 1,
  "configVersion": 2
}
```

每次成功修改、启停或删除都使 configVersion 加 1。下一次写请求需要使用响应或重新查询得到的新版本；旧版本返回 409，并且不会覆盖已有配置。修改失败时事务回滚。

删除设置 deleted_at、停用任务并增加版本；不会删除物理记录。已删除任务不出现在列表、详情和统计中。相同本地事务写入删除墓碑 Outbox，后台异步同步给 Scheduler；可通过 `/api/v1/job-sync/selectByJobId` 查询删除同步状态。

停用提交本地配置和同步事件后返回，不等待 Scheduler；同步后停止后续计划触发，不终止已经创建或运行中的任务。与触发并发时，按 Scheduler 计划行锁的取得顺序处理。

## 统计和时间预览

GET /api/v1/jobs/selectSummary 返回 data：

```json
{
  "total": 0,
  "enabled": 0,
  "disabled": 0,
  "manual": 0,
  "cron": 0
}
```

POST /api/v1/jobs/preview：

```json
{
  "cronExpression": "0 0 9 * * *",
  "timeZone": "Asia/Shanghai",
  "from": "2026-10-26T00:00:00Z",
  "count": 2
}
```

from 默认当前时间，count 默认 5、范围 1–10。计算严格晚于 from 的触发点。data 返回 timeZone 和 UTC 格式的 nextFireTimes；上例对应北京时间每天 09:00，即 2026-10-26T01:00:00Z 和 2026-10-27T01:00:00Z。

## 处理器配置

GET /api/v1/job-handlers/select 返回配置允许的标识列表。默认是 demoSuccess、demoFail、demoSlow、demoFailOnce，可通过 TASKFLOW_ADMIN_ALLOWED_HANDLER_KEYS 覆盖，逗号分隔。

这些是配置允许项；Worker 已实现相同的四个示例 Handler，并在节点注册时上报实际能力。配置允许项仍不是在线节点列表；当前能力、心跳和状态通过节点查询接口获取。新增任务保存配置和异步同步事件；已启用 CRON 副本到期后可自动创建 Run，详情中“立即执行”则另外提交手动 Run。

## 手动执行与运行历史

POST /api/v1/runs/insert：

```json
{
  "jobId": 1,
  "configVersion": 1,
  "requestKey": "84f1ab0f-c5d8-4ba0-8708-0d1a7737323a"
}
```

返回 HTTP 202 / code=OK，data 包含 id、jobId、configVersion、requestKey、state、snapshot、attempts、resultCode、message、createdAt、updatedAt。接收成功代表 Scheduler 已持久化 Run 与首次 Attempt，不代表 Handler 已成功。手动执行可以用于停用配置；enabled 管理未来的自动调度。请求使用数据库权威版本，不依赖详情展示缓存。

同 requestKey 的原请求重复提交返回同一个 Run；不同任务或版本复用同 key 返回 409 / IDEMPOTENCY_CONFLICT。新请求版本过期返回 409 / JOB_VERSION_CONFLICT。Scheduler 不可用或调用超时返回 503 / SCHEDULER_UNAVAILABLE，尚未配置内部密钥时返回 503 / SERVICE_NOT_CONFIGURED。超时后不要自动换 key：可 GET /api/v1/runs/selectByRequestKey 查验，或用原请求重试。管理台将未确认的请求保留在当前浏览器会话，关闭并重新打开详情仍可重试。

GET /api/v1/runs/select 的 jobId 必须为正数，page 默认 1、范围 1–1000000，pageSize 默认 10、范围 1–100。data 为 records、total、page、pageSize；records 包含运行 id、jobId、configVersion、state、attemptCount、resultCode、message 和创建/更新时间。运行历史来自 Scheduler，即使 Job 后来删除也继续保留。

GET /api/v1/runs/selectById 与 selectByRequestKey 返回同一详情结构；不存在返回 404 / RUN_NOT_FOUND。snapshot 包含原配置的名称、描述、处理器、参数、执行策略、Cron、时区和配置版本。后续编辑 Job 不改变已有 Run。attempts 按 attemptNumber 排序，包含 id、runId、leaseVersion、state、dispatchCount、nextDispatchAt、expiresAt、startedAt、finishedAt、resultCode、message，以及 nullable 的 targetWorkerId、leaseUntil、ownerWorkerId、ownerSessionId。当前 startedAt 为开始派发时间；expiresAt 是不可延长的硬截止，leaseUntil 是执行资格短租约。

Worker 内部续租使用 `POST /internal/v1/attempts/updateLease`，请求含 attemptId、runId、leaseVersion、workerId、sessionId，使用结果上报密钥。返回 HTTP 200，包含身份字段与 serverTime、leaseUntil、executionDeadline、hardDeadline；校验失败返回 400，鉴权失败 401，过期/终态/执行者或版本不匹配返回 409 / STALE_LEASE。该接口不经 Gateway 公开。领取与续租不改变原派发消息或 leaseVersion；续租次数无上限，但不可越过 hardDeadline。完整协议及 Handler 幂等/fencing 约定见 [执行租约](execution-leases.md)。

`nextDispatchAt` 也表示下一次派发资格检查时间。目标节点离线、Handler 或路由不匹配时，该时间延后（默认 2s）；未曾派发的 Attempt 保持 READY、dispatchCount 不增加、expiresAt 仍为空。已领取执行租约的重派发等待保留原 expiresAt，过期仍会终结。不会因等待创建额外 Attempt，也不改变本节接口路径、请求参数或响应字段。配置和验证见 [扫描公平性](dispatch-fairness-verification.md)。

Run 的 state 为 READY、RUNNING、RETRY_WAIT、SUCCEEDED、FAILED。明确的 Handler 失败可按快照配置新增 Attempt 重试；人工再次执行总是新建 Run。接收/发布不确定不立即创建业务重试；Worker 已上报的超时或重启失败保持非重试。Scheduler 观察到租约失效时，默认按快照上限新增 Attempt，`TASKFLOW_RETRY_ON_LEASE_LOSS=false` 可关闭。内置 demoSlow 可用 payload.durationMillis 控制耗时，demoFailOnce 的首次尝试失败，后续尝试成功。内部认证、超时、租约和故障边界见 [执行协议](execution-protocol.md)，实际验收见 [执行验证](execution-verification.md)。

Worker 本地结果保存失败会有限重试保存同一份结果，不重新执行 Handler，不增加 Run / Attempt 或业务重试次数。恢复后按原租约上报；已失效租约不会被延长或绕过。持续保存失败可能最终表现为 Scheduler 租约超时，不能据此断言业务未执行。公开路径、请求参数与响应结构不变；日志诊断和完整故障边界见 [Worker 结果保存恢复](worker-result-recovery.md)。

2026-10-27 状态并发修复保持上述路径、请求字段和响应结构不变。派发确认不能覆盖 SUCCEEDED/FAILED；并发收到内容相同且租约版本匹配的已接受终态时，仍幂等确认，旧租约或冲突终态仍返回 409 / STALE_ATTEMPT。重复确认不会增加 Attempt。MySQL 行锁、条件更新和回滚边界见 [状态并发修复验证](run-state-concurrency-verification.md)。

## 运行统计语义

GET /api/v1/runs/selectSummary?from=2026-10-30T00:00:00Z&to=2026-10-31T00:00:00Z 需要 run:read，返回 ApiResponse。内部 GET /internal/v1/runs/selectSummary 使用控制密钥，返回相同 data 的裸 JSON，不经 Gateway 公开。Admin 在事务外请求 Scheduler；时间窗非法为 400 / INVALID_ARGUMENT，Scheduler 不可用为 503 / SCHEDULER_UNAVAILABLE，缺少必要结构为 503 / SCHEDULER_PROTOCOL_ERROR。统计不缓存。

```json
{
  "from":"2026-10-30T00:00:00Z", "to":"2026-10-31T00:00:00Z", "observedAt":"2026-10-31T08:00:00Z",
  "total":42, "stateCounts":{"READY":1,"RUNNING":2,"RETRY_WAIT":1,"SUCCEEDED":34,"FAILED":4},
  "finished":38, "succeeded":34, "failed":4, "successRate":0.8947,
  "attemptTotal":47, "retriedRunCount":5,
  "latency":{"avgMillis":1240,"p95Millis":4300,"sampled":38,"truncated":false},
  "failures":[{"jobId":7,"resultCode":"HANDLER_FAILED","runCount":3,"lastFailedAt":"2026-10-31T07:12:00Z"}]
}
```

- 时间窗按 `run_execution.created_at` 过滤，`from` 含、`to` 不含，均为带时区的 UTC ISO-8601（以 `Z` 结尾）。
- `from`、`to` 要么都省略（默认最近 24 小时），要么都提供；只给一个为 400。
- 窗口必须 `from < to` 且跨度 ≤ 7 天，否则 400 / `INVALID_ARGUMENT`。
- Run 状态只有 `READY / RUNNING / RETRY_WAIT / SUCCEEDED / FAILED`；`TIMED_OUT` 只属于 Attempt。
- `finished` = `SUCCEEDED + FAILED`；`successRate = succeeded / finished`，四舍五入 4 位小数，`finished = 0` 时为 `null`（不要返回 0 或 NaN）。
- Run 耗时 = `run_execution.finished_at - started_at`，**包含排队、派发与重试间隔，不等于 Handler 执行时间**。
- `p95` 用 nearest-rank。为控制查询量，只取窗口内按 `created_at, id` 升序的前 10,000 个已完成 Run 作为样本；`sampled` 返回实际样本数，`truncated` 表示样本已截断（此时 p95 为近似值，文档必须写明）。
- 失败列表按 `(jobId, resultCode)` 聚合，`resultCode` 为空时记作 `UNKNOWN`，按 `runCount` 倒序，最多 5 条。
- 四个统计查询是同一 Scheduler 数据库上的多次读取，**不是一致性快照**；`observedAt` 只表示观察时刻。

我将四次读取划分为状态与完整窗口平均耗时、Attempt 总量与重试 Run 数、耗时样本、失败分组。attemptTotal 统计窗口内 Run 的所有 Attempt，retriedRunCount 为存在 attempt_number > 1 的不同 Run 数；不按 Attempt 自身时间过滤。stateCounts 始终补齐五个状态键。失败组同数量时按 jobId、resultCode 排序，lastFailedAt 取组内 Run 的最大 finished_at，null 和空字符串均归 UNKNOWN。

avgMillis 使用整个窗口内具有开始及结束时间的终态 Run 算术平均，四舍五入到整数毫秒；p95Millis 使用同样有效的耗时样本，按 nearest-rank 取值。没有可用耗时样本时平均和 P95 均为 null，sampled 为 0。采样超过 10,000 时只保留最早样本，不作随机抽样。有效耗时记录总数超过上限时 truncated=true。由于读取间状态可能变化，计数和样本数可能短暂不一致。

started_at 首次进入 RUNNING 时写入，故耗时仅涵盖此后发生的等待、派发与重试间隔，不含初始排队；历史回填以 Attempt 最早开始为准。快速回调先于 RUNNING 完成时开始时间可能为空，此类 Run 计入结果，不进入耗时统计。详见 [运行统计设计](run-reporting.md) 与 [验证记录](run-reporting-verification.md)。

## 代码分层

独立前端 taskflow-web 已接入本文接口；通过 Nginx 同源 /api/v1 调用，不在 JavaScript 中写入数据库凭据或服务绝对地址。页面入口与更新步骤见 [前端部署](frontend-deployment.md)。详情查询使用独立的缓存查询用例，其余读写仍直接访问数据库；见 [访问流程](request-flow.md)。

- job/controller：按功能拆分 Controller，接收路径与参数、调用 Service 接口、转换响应。
- job/service：IJobService、IJobDetailService、ISchedulePreviewService、IHandlerService 接口；对应实现统一位于 job/service/impl。
- job/mapper：JobDataMapper、JobCacheMapper 数据访问接口与 JobMapper SQL 接口；mysql、redis 子目录分别放对应实现，见 [持久层约定](persistence.md)。
- job/dto、model、entity、config：分别存放 HTTP 数据对象、业务模型、数据库实体与框架配置。
- system/controller、service、service/impl：基础接口及其 Service；system/dto、exception、filter 分别处理响应、异常映射和请求追踪。
- run/controller、service、service/impl、client：手动执行与历史查询；仅通过 Scheduler HTTP 协议协作，不访问 Scheduler 表，因此 Admin run 模块不创建本地 Mapper。

| Controller | 接口职责 | 调用的 Service 接口 |
| --- | --- | --- |
| JobController | /insert、/update、/enable、/disable、/delete | IJobService |
| JobQueryController | /select、/selectById、/selectSummary | IJobService、IJobDetailService |
| SchedulePreviewController | /preview | ISchedulePreviewService |
| HandlerController | /api/v1/job-handlers/select | IHandlerService |
| HelloController | /api/v1/hello | ISystemService |

我们将 HTTP 协议与内部数据访问分层隔离，使目录调整不影响接口的方法、路径、参数或 JSON 字段。Controller 不注入 Mapper；Service 实现通过 Mapper 访问本服务的数据库或 Redis。完整目录与验证见 [结构调整记录](structure-verification.md)。

## 配置异步同步

保存、修改、启停和删除的响应确认 Admin 本地提交，不等待 Scheduler。每次写入在同一事务产生带版本的不可变 Outbox 事件，后台独立发送。Scheduler 暂时不可用不会阻止保存；Outbox 写入失败则整个保存回滚。

| 方法 | 路径 | 参数与行为 |
| --- | --- | --- |
| GET | /api/v1/job-sync/selectByJobId | 查询参数 `id`，返回最新版本的同步状态，支持已删除 Job |
| GET | /api/v1/job-sync/selectPlanByJobId | 查询参数 `id`，通过 Scheduler 查询实际保存的计划版本、时区、nextFireTime、calculatedAt、calculationState；不使用详情缓存 |
| POST | /api/v1/job-sync/updateRetry | JSON `{"id":7,"configVersion":2}`，仅重新排队最新失败事件；返回同步状态 |

返回沿用 `ApiResponse`，`data` 含 `eventId`、`jobId`、`configVersion`、`deleted`、`state`、`attempts`、`nextAttemptAt`、`leaseUntil`、`lastErrorCode`、`acknowledgedVersion`、`syncedAt`、`updatedAt`。事件标识为 UUID，时间为 UTC ISO 8601，未发生的确认/租约/错误可为 null。

`state` 为 `PENDING/SENDING/SYNCED/SUPERSEDED/FAILED`。`attempts` 为当前重试周期的发送次数，人工重试后归零；`nextAttemptAt` 仅在 PENDING 时表示下次可发送时间，SENDING 使用 `leaseUntil`。无记录返回 404；版本过期或事件不在 FAILED 状态返回 409；参数不合法返回 400。

状态查询直接读数据库，不进入 Job 详情缓存。SYNCED 表示 Scheduler 确认该版本的计划副本，不代表触发开关已开启或任务已执行成功。内部 `/internal/v1/plans/update` 和副本查询协议、重试次数、超时、迁移与恢复边界见 [配置同步协议](config-sync.md)。

计划查询的 `data` 包含 `jobId/configVersion/deleted/enabled/scheduleType/cronExpression/timeZone/calculationState/nextFireTime/calculatedAt/updatedAt/automaticTriggerEnabled`。`READY` 必须带下一计划时间；其他计算状态无下一时间。automaticTriggerEnabled 表示响应 Scheduler 节点的到期扫描开关，不代表整个集群暂停或 Worker 在线。查询不创建 Run、不重新计算；无副本为 404，Scheduler 不可达或响应格式异常为 503。规则见 [计划计算](plan-calculation.md)。

Run 分页、按 ID 与 requestKey 查询响应新增 `triggerType`（MANUAL/CRON）及 `scheduledAt`（定时轮次 UTC ISO 8601 时间；人工为 null）。人工创建请求格式不变，独立人工请求仍创建新 Run；定时失败重试复用 Run，新增 Attempt。自动触发没有新增对外写接口，由后台扫描完成。并发去重、错过宽限、事务与开关见 [自动调度](automatic-scheduling.md)。

内部 `POST /internal/v1/attempts/updateResult` 请求为 attemptId、runId、leaseVersion、status、resultCode、message、retryable、workerId、sessionId；后两项必填且使用本地持久化的执行身份，使用结果上报密钥。缺少字段为 400，目标/已领取会话/租约/状态不匹配为 409。相同终态重报也须匹配原执行身份。部署及故障语义见 [Worker 水平扩展](worker-horizontal-scaling.md)。

## Worker 节点管理

| 方法 | 路径 | 参数 |
| --- | --- | --- |
| GET | /api/v1/workers/select | keyword（节点标识/名称），state=ALL/ONLINE/OFFLINE，page=1，pageSize=10（最大 100） |
| GET | /api/v1/workers/selectById | workerId，小写稳定身份 |

Admin 通过 Scheduler HTTP 查询，不访问 Scheduler 表或 Job 详情缓存。分页返回 records、筛选后 total、page/pageSize、全局 summary 和 observedAt；详情与列表记录展示 generation、Handler、容量、最近负载、路由、状态及心跳时间，不提供 sessionId 或密钥。Scheduler 不可用或响应格式不合法返回 503，节点不存在返回 404，参数错误返回 400。

内部 `POST /internal/v1/workers/insert`、`POST /internal/v1/workers/updateHeartbeat` 使用独立节点密钥；内部两个 GET 查询使用控制密钥。完整注册/心跳字段、超时、幂等、乱序和旧会话语义见 [节点协议](worker-nodes.md)。节点在线不代表具体任务执行成功，generation 不等于 Attempt leaseVersion。

## 派发通道

任务派发默认使用 `taskflow.dispatch.v1` 消息事件，对外手动执行、运行查询与结果上报 HTTP 路径不变。Broker 确认只表示接管派发，不代表执行成功；Worker 满载时等待原消息，不计故障重试次数、不转死信；租约过期的合法投递 ACK 清理，原 Attempt 由 Scheduler 收尾为超时，不标记成功。节点查询的 `dispatchEligible` 同时要求最近心跳 `runningCount + pendingCount < maxPending`，满载仍为 ONLINE。非法事件与实际故障耗尽的死信、未确认派发不会自动新建业务 Run。事件结构、失败语义及切换步骤见 [可靠派发](reliable-dispatch.md)。真实进程退出与迟到上报验证见 [故障窗口记录](dispatch-fault-verification.md)；旧 Worker 收到结果上报 409 后停止重报，Inbox 的 reported 标记不表示 Scheduler 接受旧业务结果。

## 技术参考

- [Spring MVC 请求校验](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)
- [MyBatis-Plus Spring Boot 4 安装](https://baomidou.com/getting-started/install/)
- [Spring Boot JSON 与 Jackson 3](https://docs.spring.io/spring-boot/reference/features/json.html)

## 独立订单示例 API（v1）

我将以下接口部署在独立订单服务（默认本机 8083）。公共 Gateway 当前不路由此业务示例。除健康探针外均要求 `X-Taskflow-Token`；业务 API 使用 `TASKFLOW_ORDER_API_TOKEN`，内部超时接口使用另一个 `TASKFLOW_ORDER_WORKER_TOKEN`，空令牌拒绝所有请求。业务令牌仅用于受控演示，包含初始化和模拟付款权限，不作为真实用户支付鉴权方案。

| 方法与路径 | 参数 | 行为 |
| --- | --- | --- |
| POST `/api/v1/inventory/insert` | JSON `skuId`（正整数）、`available`（0–10亿） | 初始化 SKU，重复 SKU 返回 409 |
| GET `/api/v1/inventory/select` | query `skuId` | 库存快照：skuId、available、reserved、version；可能在 TTL 内滞后 |
| POST `/api/v1/orders/insert` | JSON `requestKey`（非空，最多100字符）、`skuId`、`quantity`（1–100万） | 幂等下单并预占；同键不同参数或库存不足返回 409 |
| GET `/api/v1/orders/select` | query `id` | 返回 `order` 与业务 `ledger`；不存在返回 404 |
| POST `/api/v1/orders/updatePayment` | JSON `orderId` | 受保护的模拟付款；已付款幂等；过期/已关闭返回 409 |
| POST `/internal/v1/orders/updateTimeout` | JSON `batchSize`（1–500）、可选 `cutoff/afterExpiresAt/afterId`；必填 `X-Request-Id`（1–128字符） | 逐单短事务关单；返回 scanned/closed/skipped/failed 与继续扫描游标 |

订单保存单个 SKU 明细、创建/付款截止/付款/关闭时间、关闭原因。期限由服务 `order.payment-window`（默认30分钟）在创建时确定。订单状态为 PENDING/PAID/CLOSED。付款必须严格早于截止时间；超时关单使用小于等于截止时间的候选并在订单锁内复核。内部请求首次不传游标，后续原样携带返回的 cutoff/afterExpiresAt/afterId；cutoff 不允许未来时间，游标两字段必须成对提供。非法参数返回 400；鉴权失败 401；未知服务故障 5xx。调用方不得自动重放 POST。

示例请求体（均发送至对应 POST 路径）：

```json
{"skuId":1001,"available":10}
```

```json
{"requestKey":"checkout-001","skuId":1001,"quantity":2}
```

```json
{"batchSize":100}
```

Worker Handler 参数为 `{"batchSize":100,"maxBatches":10}`，上限分别500和100，旧 `timeoutMinutes` 参数拒绝执行。节点仅在 `taskflow.order.enabled=true` 时注册能力。业务流水记录请求关联键；该键不替代订单/预占唯一约束。任务成功表示本轮有限预算处理完成，具体订单结果以本服务流水为准。

## 销售日报 API（v1）

公共查询经 Nginx/Gateway/Admin，要求 JWT、有效 Redis 会话和 run:read。响应沿用 code/message/data/requestId；金额字段 totalAmountCents、unitPriceCents 均为人民币整数分，日期为 ISO 日期，时间戳为 UTC Instant。

| 方法与路径 | 参数 | data |
| --- | --- | --- |
| GET /api/v1/sales-reports/select | 可选 shopId；page 默认1、范围1–100万；pageSize 默认20、范围1–100 | items、total、page、pageSize |
| GET /api/v1/sales-reports/selectById | id（报表 UUID） | report 与 items（商品明细） |
| GET /api/v1/sales-reports/selectExport | id | filename、content；前端将 UTF-8 CSV 内容下载为文件 |

Report 字段：id、shopId、businessDate、timeZone、orderCount、totalQuantity、totalAmountCents、generatedAt。Item 字段：skuId、productName、orderCount、totalQuantity、totalAmountCents。暂无日报返回空 items；合法的零销售日报保留零汇总；上游故障或损坏响应返回 503，不伪造空报表。未知报表返回404，无读权限403，参数错误400。

以下接口仅在独立订单服务开放，公共 Gateway 不转发；成功时直接返回业务对象，不套 Admin 响应信封。

| 方法与路径 | 鉴权与参数 | 行为 |
| --- | --- | --- |
| POST /internal/v1/sales-reports/insert | X-Taskflow-Token 使用订单 worker-token；JSON requestKey（1–100字符）、shopId（正整数）、可选 businessDate、timeZone（合法 ZoneId，最多100字符） | 固定统计日期并幂等保存日报，返回 Report |
| GET /internal/v1/sales-reports/select | worker-token；同公共分页参数 | 返回 Page |
| GET /internal/v1/sales-reports/selectById | worker-token；id | 返回 Detail |
| GET /internal/v1/sales-reports/selectExport | worker-token；id | 返回 Export |
| POST /api/v1/sales-samples/insert | 订单 api-token 且 order.samples-enabled=true；JSON dataset（小写字母开头、最多32字符，仅字母数字连字符）、startDate、days（1–90）、orderCount（1–10000）、seed（long） | 事务导入并返回批次参数；样例日期区间必须已经结束 |

生成只允许已结束的业务日，未指定日期取首次成功绑定时任务时区的前一天；同 requestKey 改店铺/原始日期/时区返回409；未知店铺404；同范围不同 Run 返回同一份报表。每日快照不自动包含生成后的后补/迟到数据，未建成交快照的旧订单不计入。样例同 dataset 同参数不重复导入，不同参数409，关闭样例入口时404。详细事务、时区、幂等及故障边界见 [销售日报](sales-reports.md)。

Worker salesReport 参数示例为 `{"shopId":8}`，回补示例为 `{"shopId":8,"businessDate":"2026-11-01"}`；默认关闭，开启 taskflow.sales.enabled 并配置订单 worker-token 后注册实际能力。HTTP POST 不自动重放，有限业务重试复用 Run 操作键。

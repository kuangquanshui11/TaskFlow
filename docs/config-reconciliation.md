# 配置对账与受控修复

我们将历史发送确认与当前副本核实分开：`SYNCED` 只记录曾经接收，不能证明 Scheduler 当前仍有副本。Admin 从自己的权威配置（包含软删除记录）分批读取 Job ID、版本、删除/启用标志及对应 Outbox 事件，再通过 Scheduler v1 API 查询实际副本；两个服务不共享表、事务或实体。

## 对账

`POST /api/v1/config-reconciliation/insert` 使用 JSON：

```json
{"requestId":"b6e0128d-3f3c-42be-968d-53a4ba732e7a","afterJobId":0,"batchSize":100}
```

我们使用递增 Job ID 游标，最多每批 100 个，包含删除墓碑。返回 `requestId/afterJobId/batchSize/nextAfterJobId/hasMore/state/actor/startedAt/leaseUntil/finishedAt/checks`。下一批使用新 requestId 和上一批 nextAfterJobId；hasMore=false 表示该次读取没有更多记录。每批是独立观察，并非跨服务或全表的一致快照；并发变更可能发生在已扫描部分，完成遍历后可从 0 再次检查。

`GET /internal/v1/plans/selectVersions?ids=7,8` 使用 `X-Taskflow-Token` 控制密钥、关联 `X-Request-Id`，返回 JSON 数组 `[{"jobId":7,"configVersion":2,"deleted":true,"enabled":false}]`。成功响应只包含存在的副本，缺少的 ID 才能判为 MISSING；空数组是合法结果。请求必须包含 1..100 个正数 ID，返回包含墓碑且不读取 Inbox 来推测副本。Gateway 不开放该接口。

我们复用配置同步客户端的连接/请求超时（默认 2s/3s）、Feign 无隐式重试及 cloud 服务发现/熔断。查询在 SQL 事务外进行，一批只有一次远程请求。整个响应必须通过类型、版本、ID 范围和去重校验；网络错误、认证失败、旧服务缺少新接口、熔断及非法响应均记录 UNAVAILABLE，不推断为丢失。

| difference | 判定 | 允许修复 |
| --- | --- | --- |
| MATCH | 版本、删除与有效启用标志相同 | 否 |
| MISSING | 合法成功响应未包含该 Job | 是 |
| BEHIND | Scheduler 版本小于 Admin 版本，包括待补发墓碑 | 是 |
| AHEAD | Scheduler 版本大于 Admin 版本 | 否，需检查恢复来源 |
| CONFLICT | 同版本删除或启用标志不同 | 否，需人工排查 |
| SOURCE_MISSING | Admin 当前版本缺少对应 Outbox 快照 | 否，需检查本服务数据 |
| UNAVAILABLE | 无法核实 Scheduler | 否 |

每条检查保存 `id/scanId/source/replica/difference/errorCode/checkedAt/repairRequestId`，source 含 `jobId/configVersion/deleted/enabled/eventId`，replica 含 Scheduler 观察值；丢失或无法查询时为 null。相同版本并不校验完整配置内容指纹；本能力不声称发现任意字段损坏。

## 修复

`POST /api/v1/config-reconciliation/updateRepair` 使用新的请求 UUID 和检查 ID：

```json
{"requestId":"a0b31ed8-fc18-49a4-8707-9cd0b7a71621","checkId":"31a39419-423a-455b-9cbb-4703ed62058b"}
```

我们只接受最近 5 分钟内的 MISSING/BEHIND 观察。在短本地事务中锁定 Admin 当前 Job（包含墓碑），用当前读重新比较版本、标志和事件 ID；随后用条件 UPDATE 将原 Outbox 终态事件重新排队，同时提交修复记录。任一数据库写入失败，排队和记录一起回滚。该事务不调用 Scheduler。

修复沿用原 eventId、版本和不可变快照，不新增配置版本、Job 或 Run。我们清空该事件旧确认、错误及租约字段，并重置这次有限发送周期的尝试计数。已有 PENDING/SENDING 交给正常 Relay，不清空有效发送租约。Relay 继续使用独立发送租约、默认最多 5 次的退避重试和持久化确认。ENQUEUED 表示发送意图已保存；需要查询同步状态并重新对账才能核实副本修复，不能将排队当作修复完成。

| outcome | 含义 |
| --- | --- |
| ENQUEUED | 原事件已重新进入 Outbox |
| ALREADY_PENDING | 原事件已等待或正在发送 |
| ALREADY_REQUESTED | 同一检查已消费，不再次重置发送次数 |
| STALE | 本地配置在对账后已改变或消失，应重新对账 |
| EXPIRED | 检查已超过有效期，应重新对账 |
| NOT_REPAIRABLE | 观察结果不允许自动修复 |

每个修复结果均返回并保存 `requestId/checkId/eventId/outcome/actor/createdAt`。查询使用 `GET /api/v1/config-reconciliation/selectRepairById?id=<requestId>`；检查结果中的 repairRequestId 关联第一次有效修复。普通查询不会触发修复。

Scheduler 仍使用 `POST /internal/v1/plans/update` 的原事件协议和内容指纹保护。如果 Inbox 留存而副本缺失或版本落后，匹配的原事件可以重新应用；若副本已有相同/更高版本，继续返回 DUPLICATE 且保留计划时间。Inbox 保留的最高已接收版本同时作为恢复下界；缺失副本不能被迟到的旧配置抢先重建，需补发最高版本原事件，否则返回 409 `PLAN_RESTORE_REQUIRES_LATEST`。重建缺失副本按接收时刻计算下一次计划，不根据历史同步时间批量补建 Run。版本较旧的事件不能覆盖较新墓碑；事件标识或版本对应的内容冲突仍返回 409。数据库提交、HTTP 和 Admin 确认之间继续非原子，通过同一事件重放恢复。

## 限速、幂等与记录

我们在 Admin 数据库持久化 scan、repair 两个限速闸门；所有实例通过行锁串行领取。默认一批对账完成后等待 10 秒，修复意图每秒最多一条。批量上限及间隔来自 `taskflow.reconciliation`，服务重启后不会清空限速。正常配置变更的 Outbox 不受此闸门影响，其 Relay 仍按已有批量和扫描周期发送。

对账先保存 RUNNING 批次和租约（连接超时 + 请求超时 + 30 秒余量），再释放 SQL 事务执行查询，最后在新事务中原子提交 COMPLETED 和全部检查。进程失联或超出租约时查询显示 EXPIRED，不发布部分检查，也不释放其他批次的窗口；租约到期后可用新 UUID 重扫。同 UUID 始终查询原批次，不能用于重新核实。意外错误可能在租约到期前暂留 RUNNING。

相同扫描 requestId/参数或相同修复 requestId/checkId 重放只返回已有记录，幂等查询优先于限速；相同 UUID 携带不同参数返回 409。一个检查最多消费一次有效修复；再次检查使用新批次。限速返回 429 `RECONCILIATION_RATE_LIMITED`，可稍后显式使用原 UUID 重试，传输层不重放 POST。不存在的批次/检查/修复为 404，参数不合法为 400。成功的观察和修复结果使用 HTTP 200 与 Admin ApiResponse，拒绝修复原因体现在 outcome。被认证、校验或限速拦截的请求不创建业务修复记录。

VIEWER 可查询已保存的批次与修复记录；OPERATOR/ADMIN 具有 `job-sync:reconcile` 和 `job-sync:repair`，可发起对账与修复，HTTP 和 Service 均检查权限。actor 使用认证主体，忽略客户端伪造的操作者。检查及修复记录保存在 Admin V4 的独立表，默认保留，不自动归档。

## 升级与操作

1. 备份两个服务的数据库及本机配置；先升级 Scheduler，使批量查询及原事件恢复分支可用。
2. 升级 Admin，由 Flyway 执行 V4；默认参数随 `application.yml` 提供，覆盖项只存非秘密策略。local/test 使用直连，cloud 使用原配置同步服务发现客户端。
3. 从 afterJobId=0 分批对账，通过 selectById 核实返回。只对 MISSING/BEHIND 明细显式提交修复，查看记录与 job-sync/selectByJobId，然后重新对账。
4. 查询失败先修复认证、网络或服务版本；AHEAD/CONFLICT/SOURCE_MISSING 先排查数据来源，不自动降版本或覆盖同版本内容。修复发送耗尽后排查 Outbox 错误；可重新对账后再申请有界发送周期。

本能力遍历 Admin 已知配置及墓碑，不发现仅存在于 Scheduler 的未知 Job；不包含数据库整库恢复、自动定时全量扫描、孤立副本自动删除、内容任意损坏修复或控制台新页面。Admin 已硬删除的墓碑也不在遍历范围，需要通过备份/数据保留策略恢复权威来源。

若 Scheduler 的副本与 Inbox 同时丢失，就不存在可验证的历史版本下界。整库恢复时应先用 `TASKFLOW_PLAN_TRIGGER_ENABLED=false` 关闭自动触发，核实权威配置重建和在途旧事件后再开启；单次受控修复不替代此恢复流程。

验证命令、证据和实际边界见 [对账验证记录](config-reconciliation-verification.md)。

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
| CONFLICT | 同版本删除标志、启用标志或快照内容不同 | 否，需人工排查 |
| SOURCE_MISSING | Admin 当前版本缺少对应 Outbox 快照 | 否，需检查本服务数据 |
| SOURCE_INVALID | Admin 当前版本的 Outbox 无法解析为快照对象 | 否，需检查本服务数据 |
| ORPHAN | Scheduler 存在副本，Admin 缺少对应 Job 和墓碑 | 否，需人工排查 |
| UNAVAILABLE | 无法核实 Scheduler | 否 |

每条检查保存 `id/scanId/source/replica/difference/errorCode/checkedAt/repairRequestId`。源记录包含 Job ID、版本、删除/启用标志、事件 ID 和指纹，副本包含当前观察值。匹配版本下，删除、启用标志或快照指纹不同均为 CONFLICT，无法解析的源快照为 SOURCE_INVALID。手工对账连接旧版 Scheduler 时沿用版本和标志检查。

## 自动全量对账

我复用手工对账的差异与补发记录。开启 Admin 的 `TASKFLOW_RECONCILIATION_AUTOMATIC_ENABLED=true` 后，后台线程按 Job ID 执行双向分页：

1. 每轮先记录 Admin 的 Job ID 上界，分页读 Job 和对应 Outbox，再查 Scheduler 副本。SHA-256 输入为快照对象的字段按键排序后的 UTF-8 JSON，`payloadJson` 保留协议中的字符串内容。Scheduler 每次根据实际保存的 `schedule_plan.snapshot_json` 计算指纹，兼容旧行。计划计算状态、下一触发时间和 Inbox 历史指纹不参与比较。
2. Admin 方向结束后调用内部 `selectReplicaPage`，首批获取 Scheduler 的 Job ID 上界，后续批次沿用上界。Admin 用本地 Job 表核实返回的 ID，包括软删除记录，权威记录不存在时记录 ORPHAN。ORPHAN、AHEAD、CONFLICT 和源数据问题由操作员排查。
3. 每次运行最多一页、一次远程请求，默认 100 条，后台 SQL 租约与随机令牌防止多实例重复提交或过期写入。差异与游标在同一事务保存。不可用、非法响应或缺少指纹时保留游标和错误码，默认 30 秒后重试。正常批次间隔默认 5 秒，轮次完成后默认 1 小时再扫。各时间都由 Admin YAML 配置。

两个方向各自捕获上界，各批独立观察。并发变化可在下一轮核实。进度累计的是两个方向各自读取的条数，正常配置可能被计入两次。只保留自动轮次的差异明细。控制台可刷新最新轮次、分页查看差异，并对有效期内的 MISSING/BEHIND 使用原事件补发和手工复查。对账的 Job ID 游标只用于续扫当前轮次，已扫描 Job 的后续变更由正常 Outbox 推送或下一轮全量对账发现。

## 配置事件增量追赶（P03，未实现）

我将全局配置事件增量追赶保留为独立开发项。当前 `eventId` 是事件 UUID，`configVersion` 只在单 Job 内递增；Outbox 的待发送排序和全量对账的 `afterJobId` 均不提供全局事件消费断点。已有对账、受控补发和维护恢复的验证只覆盖各自边界。

后续实施需先确定以下协议和持久化规则；当前没有增量查询 API、全局消费断点或相应迁移：

1. **事件顺序与安全水位。** 我需要一个可分页且不会遗漏迟提交事务的事件位置，保留原 `eventId/jobId/configVersion`、不可变快照和删除墓碑。自增 ID 的分配顺序不代表事务提交顺序，不能读取到较大 ID 后直接跨过仍未提交的较小 ID；时间戳或 UUID 排序也不提供该保证。水位必须能证明其之前的事务已提交或已回滚，并区分合法序号空洞与数据缺失。
2. **消费断点与并发。** Admin 在自己的数据库维护可追赶事件序列，通过版本化 HTTP 提供有界分页；Scheduler 在自己的数据库维护消费断点、租约及领取版本。内部协议需声明服务身份认证、连接/请求超时、失败行为和有限业务重试，禁止传输层自动重放 POST。Scheduler 在短本地事务中一起保存已处理事件的 Inbox、计划副本和连续处理断点，再返回确认；网络调用放在事务外。部分失败不能推进到失败事件之后，过期持有者不能写回断点。恢复时继续使用原事件，沿用单 Job 版本、指纹及墓碑保护，不能根据 Admin 的历史发送确认推断消费位置。
3. **归档、缺口与过期。** 我需要声明可读取事件的最早位置、批次上界、事件流身份及保留窗口，与 [历史归档](history-archive.md) 和 [P06](../尚未完成的功能.md) 的清理规则协调。正文归档后仍需可读，或明确返回断点不可续追；数据恢复导致事件流更换、断点过期、应有事件缺失时均须显式报错，不能跳到最新位置并报告追赶完成。
4. **全量恢复与增量衔接。** 无法继续增量时，我沿用受控全量对账和维护恢复边界；AHEAD、CONFLICT、ORPHAN 及源数据问题仍需人工排查。恢复协议还需建立与基线对应的安全事件水位，覆盖基线扫描期间的变更；必要时冻结配置写入，在确认副本和墓碑恢复后才建立新断点。全量恢复完成后从该水位续追，继续保留周期双向全量对账以发现数据丢失和孤立副本。

P03 验收需在真实 MySQL、独立 Admin/Scheduler schema 中覆盖：较小位置的事务晚于较大位置提交、回滚留下空洞、同一 Job 多版本及墓碑乱序、批次中途失败、Scheduler 提交前后退出与响应丢失、多实例抢占和过期写回、正文归档后续追、断点过期或事件流更换，以及全量恢复期间并发变更后的增量衔接。每个场景需同时核对事件、断点、Inbox、计划版本和墓碑，不能只检查最终最新配置相同。实现及这些场景通过后，才删除 [P03 待办](../尚未完成的功能.md)。

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

## 控制台操作

我把「配置对账」放在控制台工作空间导航和任务详情的配置同步面板中。操作员可以按任务 ID 从头分批检查，默认每批 20 条，可按部署上限调整。页面显示 Admin 和 Scheduler 各自的版本、启停和删除状态，可筛选差异，也可用批次 ID 查询保存的观察。只读账号使用批次 ID 查看结果及已关联的补发记录。

对缺失或落后的副本，操作员确认后提交原事件补发。页面展示补发结果、操作者、时间和请求 ID。「已排队」表示 Outbox 已受理。等发送结束后选择「重新核对本批」，以新的批次确认副本是否一致。对账和补发的响应未确认时，当前页面上的显式重试沿用原请求 ID。对账请求 ID 可用于查询已保存的批次。

## 升级与操作

1. 备份两个服务的数据库及本机配置；先升级 Scheduler，使批量查询及原事件恢复分支可用。
2. 升级 Admin，由 Flyway 执行 V4；默认参数随 `application.yml` 提供，覆盖项只存非秘密策略。local/test 使用直连，cloud 使用原配置同步服务发现客户端。
3. 从 afterJobId=0 分批对账，通过 selectById 核实返回。只对 MISSING/BEHIND 明细显式提交修复，查看记录与 job-sync/selectByJobId，然后重新对账。
4. 查询失败先修复认证、网络或服务版本；AHEAD/CONFLICT/SOURCE_MISSING 先排查数据来源，不自动降版本或覆盖同版本内容。修复发送耗尽后排查 Outbox 错误；可重新对账后再申请有界发送周期。

自动对账需要先升级 Scheduler，再升级 Admin，由 Flyway 应用 Admin V5。默认关闭自动对账，升级完成后按部署需求开启。Scheduler 孤立副本保留待人工排查，Admin 已硬删除的墓碑需要通过备份或数据保留策略恢复权威来源。

## 计划副本与 Inbox 双丢失恢复

我采用有维护窗口的恢复流程。前提是 Admin 的 Job、软删除墓碑和每个当前版本的原始 Outbox 事件完整，Scheduler 的 Run/Attempt 数据可以继续使用。如果 Admin 恢复到了旧时间点，或 Scheduler 的 Run 历史也丢失，应先按数据库恢复方案核实备份。孤立副本或超前版本需要人工核对来源。

1. 备份当前数据及配置，停止所有正常 Admin、Scheduler 实例，等待旧进程退出，隔离旧部署的流量。在指定维护 Admin 上设置 `TASKFLOW_CONFIG_RECOVERY_ENABLED=true`、`TASKFLOW_JOB_SYNC_ENABLED=false`。恢复模式拒绝 Job 新增、修改、删除和启停。可同时开启自动对账，并临时缩短轮次间隔以便复查。
2. 在恢复 Scheduler 上设置 `TASKFLOW_PLAN_TRIGGER_ENABLED=false`，并为所有恢复实例设置同一个未来 UTC 时刻 `TASKFLOW_PLAN_RECOVERY_RESUME_AT`，用 ISO 8601 格式并带 `Z`。恢复节点拒绝常规 `plans/update`，仅通过内部 `plans/updateRecovery` 接收恢复批次。已创建的 Run/Attempt 仍按自己的生命周期处理。
3. 以 ADMIN 调用 `GET /api/v1/config-reconciliation/selectRecoveryBounds`，取得 `upperJobId`。从 `afterJobId=0` 开始调用 `POST /api/v1/config-reconciliation/updateRecovery`，传入 `afterJobId/upperJobId/batchSize`（1..10）。成功后以 `nextAfterJobId` 继续，直到 `hasMore=false`。Scheduler 为每条原事件在本地事务内重建副本及 Inbox，并按选定时刻计算下一次 Cron。重试保留原事件 ID 和版本。批次中断时前面已成功的条目可能已提交，应排查错误后用原分页参数重试。超前或同版本冲突会使该批次中止。若超出恢复窗口，重新设置未来时刻并从第一页执行，已核实的相同版本也会按新时刻重算。
4. 确认重建后启动的自动轮次已完成且无差异，检查已删除 Job、已启用 Cron 的下一触发时间和既有 Run。保持 Cron 和 Admin Relay 暂停，移除 Scheduler 的 `TASKFLOW_PLAN_RECOVERY_RESUME_AT` 并重启。用隔离演练时记录的旧启用事件调用常规更新，核对确认版本为最新墓碑版本，计划仍显示已删除。实际维护同时核对 Admin 待投递事件和停机前请求记录，确认事件仍有对应权威 Job 或墓碑。发现缺口时维持暂停并排查。
5. 核实后恢复所有 Scheduler 的 Cron 扫描，再解除 Admin 的配置写入和 Relay 暂停，检查新的 Cron Run 和正常配置同步。计划从恢复时刻之后开始，维护窗口内的 Cron 轮次跳过。若开启扫描时已超过所选时刻，所选时刻之后的轮次仍使用原有 SKIP/FIRE_ONCE 规则。

恢复写入不修改 Admin Outbox 历史确认，恢复批次返回处理进度，以新一轮对账核实结果。操作员可从 Admin 日志按主体、Job ID 和事件 ID 定位失败批次。演练命令与结果见 [验证记录](config-reconciliation-verification.md)。

验证命令、证据和实际边界见 [对账验证记录](config-reconciliation-verification.md)。

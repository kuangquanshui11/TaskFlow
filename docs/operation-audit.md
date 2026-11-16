# Job 与手动触发操作审计

我将普通手动触发与 Job 新增、修改、启用、停用、删除的审计保存在 Admin。操作者使用验签 JWT 的 `sub`，记录包含对象标识、配置版本、动作、时间、结果码、请求标识与可用的 Trace ID。手动触发补充请求键和已确认的 Run ID。

## 写入与恢复

Job 成功审计与配置变更、配置 Outbox 同事务提交。经过参数绑定的业务拒绝在写入事务回滚后单独记录。入口认证和授权失败由安全层处理。

我在首次手动触发调用 Scheduler 前持久化 `PENDING` 记录。Scheduler 在创建 Run 的同一事务保存已认证主体。Admin 核对主体、请求键、Job ID 和配置版本后记录 `APPLIED`。同一绑定使用同一记录，已确认的结果保持稳定，业务明确拒绝记录为 `REJECTED`。

请求响应超时或服务不可用时，我通过 `selectByRequestKey` 进行只读核对。每轮最多领取 20 条，领取后间隔至少 30 秒，每条最多 6 次，未取得匹配证据的记录进入 `UNCONFIRMED`。允许客户端按已有方式再次提交同一请求键，后续确认可以补全审计。Scheduler 在本地事务内保护 Run 的请求键唯一性。历史 Run 可能缺少主体证据，审计保留未确认状态。

## 查询与部署

`GET /api/v1/audits/select` 由 Gateway 转发，Admin 路由与 Service 同时要求 `audit:read`，当前由 ADMIN 获得。审计结果按发生时间和 ID 倒序分页。普通 Run 响应省略新增的手动触发者字段。详见 [API](api.md#操作审计)。

升级时先启动含 V15 迁移的 Scheduler，再启动含 V7 迁移的 Admin，使新提交具备主体核验所需的字段。审计扫描由 `taskflow.audit.scan-enabled` 控制，默认启用。默认扫描间隔为 15 秒，可通过 `taskflow.audit.scan-interval` 调整。

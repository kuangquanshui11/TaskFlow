# 仓库库存快照同步

## 数据归属

我把库存同步放在独立订单服务的 inventory 包，从样例文件或 WMS HTTP 全量接口拉取一个仓库的库存，写入 `inventory_sync_stock` 供查询和对账。订单创建、付款、预占及释放使用原有 `sku_stock`、预占与流水。Scheduler 维护 Run 和 Attempt，Worker 通过内部 HTTP API 发起同步。V7 按来源与仓库隔离版本和镜像，将既有记录归入 `sample`，原请求键仍可重放。

内置样例是仓库 12、源版本 1、SKU 12001（80 件）与 12002（35 件）。可用 `TASKFLOW_INVENTORY_SYNC_SAMPLE_SOURCE` 指向本机 JSON，例如 `file:./.tools/inventory-snapshot.json`。更新时先写完整临时文件再替换，提供递增的正整数源版本。样例按原有合并规则处理，缺失 SKU 保留。两种来源均限制每次最多 1000 个唯一 SKU，数量为 0 到 10 亿的整数。

## WMS 全量 HTTP 契约

我用全量快照作为首期生产接入契约。订单服务向配置的 WMS 路径发送 GET，查询参数为 `warehouseId`，通过 `Authorization: Bearer <token>` 携带独立的只读凭据。接口使用 HTTPS，连接与整次请求都有超时。模拟环境可显式开启仅限 `127.0.0.1` 的 HTTP。

HTTP 200 响应示例：

```json
{
  "warehouseId": 12,
  "sourceVersion": 7,
  "complete": true,
  "totalItems": 2,
  "items": [{"skuId": 12001, "quantity": 80}, {"skuId": 12002, "quantity": 0}]
}
```

`complete` 必须为 true，`totalItems` 必须与数组长度一致，仓库号必须与请求匹配。接口需在单个响应中返回同一版本的完整仓库，解压后响应上限为 256 KiB。分页接口需由 WMS 适配层先收齐并验证同版本的所有页，再形成完整响应。只接收明确的空快照 `complete:true,totalItems:0,items:[]` 来清除仓库全部镜像行。普通全量里，缺失 SKU 移除镜像行，显式零保留镜像行并将数量置零。

我把增量留作单独的接入模式。增量需携带 `baseVersion` 和新版本，并声明逐 SKU 更新或删除动作。基准版本须等于已应用版本，缺失 SKU 保留，显式零仍代表在册零库存。遇到版本断档先恢复全量，再继续增量。当前部署使用全量接口，实际 WMS 如只有增量，需要先确认其版本和断档恢复协议。

## 重试和并发

我在事务外读取并校验库存来源，在短事务内锁定来源与仓库，比较版本并一起提交镜像、仓库版本和批次。完整全量可跨过缺失版本。新版本返回 `APPLIED`，写入条数为 upsert 行数加移除行数。同版本按 SKU 排序比较内容摘要，一致返回 `UNCHANGED`，异内容返回 409 `INVENTORY_VERSION_CONFLICT`。旧版本返回 `STALE`。后两种成功结果 `UNCHANGED` 和 `STALE` 记录批次，写入条数为零。结果提交成功后，即使 HTTP 响应丢失或已切换来源，相同请求键和仓库也返回原批次。相同键换仓库返回 409。

Worker 校验任务快照中的正整数 `warehouseId`，按 Run ID 派生请求键，在调用前检查租约与剩余执行预算。调用沿用订单服务连接超时、请求超时和内部令牌，cloud 按服务名发现，local 显式直连。HTTP POST 只发送一次。WMS 超时或暂不可用映射为 503，由 Scheduler 按 Job 的 Attempt 上限重试。WMS 拒绝凭据映射为 422，版本冲突映射为 409，两个错误均需要处理原因后发起新 Run。协议见 [API 文档](api.md#仓库库存同步-apiv1)。

## 配置 WMS 与对账

升级现有库前停止订单服务并备份订单 schema，启动新版应用 Flyway V7。先用测试仓库和只读 WMS 凭据配置 `config/taskflow-inventory-sync-local.yml`，再重启订单服务。WMS 凭据通过环境变量或被忽略的本机 YAML 提供。

```yaml
order:
  inventory-sync:
    enabled: true
    source: wms
    wms-source-id: wms
    wms-url: ${TASKFLOW_INVENTORY_SYNC_WMS_URL}
    wms-token: ${TASKFLOW_INVENTORY_SYNC_WMS_TOKEN}
    wms-connect-timeout: 1s
    wms-request-timeout: 3s
```

`wms-source-id` 用于区分独立的 WMS 版本域，接受 `wms` 或 `wms-<标识>`。同一来源持续使用同一标识。切换到另一个版本域前，先排空在途 Run，配置新标识，再手动执行一次全量并核对。WMS 请求超时应短于 Worker 调用订单服务的请求超时，为数据库写入和返回预留时间。

1. 用新 Run 同步测试仓库。通过内部 `selectStatus` 核对来源、最后确认版本和 SKU 行数，再用 `select` 抽查存在、缺失和零库存的 SKU。核对 WMS 的仓库/SKU 映射及数量口径后再启用定时任务。
2. 异常时用 `selectStatus` 的最后失败代码和请求键对应 Run。版本为 0 表示尚未确认过快照。已恢复的仓库仍保留最后一次失败，可用 `lastSyncedAt` 与 `lastFailureAt` 判断是否已有后续成功确认。旧版本响应对应 Run 的 `STALE` 批次，确认 WMS 是否切错环境或回退版本。
3. 同版本冲突时对照发布内容，确认 WMS 用更高版本重新发布正确全量。配置或上游修复后使用新 Run，复核最新版本和明细。WMS 和业务可售量存在差异时，结合现有预占、释放和库存流水人工对账。

## 本机接入

先准备订单服务的独立 MySQL schema 和账号。订单服务 `order.worker-token` 与 Worker `taskflow.order.token` 应使用同一个本机凭据。已有订单示例可以复用它的配置。先核对在途任务已结束，再构建并更新服务。

1. 在被 Git 忽略的 `config/taskflow-inventory-sync-local.yml` 写入以下样例配置。先重启订单服务应用迁移，再重启 Worker。

   ```yaml
   order:
     inventory-sync:
       enabled: true
       source: sample
   taskflow:
     inventory-sync:
       enabled: true
   ```

   ```powershell
   .\mvnw.cmd verify
   .\tools\services.ps1 -Action restart -Service order
   .\tools\services.ps1 -Action restart -Service worker
   ```

2. 在「工作节点」确认在线可派发的 Worker 上报 `inventorySync`。在同一份本机 YAML 的 `taskflow` 下追加 `jobs.allowed-handler-keys`。以下为默认列表示例，有自定义配置时保留现有标识并追加 `inventorySync`。也可通过 `TASKFLOW_ADMIN_ALLOWED_HANDLER_KEYS` 设置完整列表。

   ```yaml
     jobs:
       allowed-handler-keys: demoSuccess,demoFail,demoSlow,demoFailOnce,orderTimeoutCheck,salesReport,inventorySync
   ```

   ```powershell
   .\tools\services.ps1 -Action restart -Service admin
   ```

3. 在控制台新建「定期同步库存」，点击「刷新处理器列表」，用样例仓库 12 保存为停用。先手动运行，对照 Run 的 `batchId`、`sourceVersion` 和 `updatedCount`，通过内部查询接口核对 SKU 数量。空镜像首次同步预期 `APPLIED`，另起 Run 同步同一源版本预期 `UNCHANGED`。
4. 验证源更新时，将内置 JSON 样例复制到被 Git 忽略的 `.tools/inventory-snapshot.json`，保留已有文件，并在本机 YAML 的 `order.inventory-sync` 下设置 `sample-source: file:./.tools/inventory-snapshot.json`。重启订单服务加载路径。随后将完整的新快照写到临时文件再替换，提高源版本并更新一个 SKU 的数量。再手动运行，核对 `APPLIED`、新版本和镜像数量。每个新请求会重新读取文件。
5. 手动验证完成后启用每 5 分钟的计划。确认计划副本已同步，等待下一个计划时间，在运行记录中核对触发方式为定时、状态为成功。若在线节点不可派发，先检查 Worker 健康与 Handler 注册。在 RabbitMQ 模式下同时检查 Broker 连通性。切换派发方式前先排空在途工作。 [可靠派发](reliable-dispatch.md)

## 验证命令

```powershell
.\mvnw.cmd verify
.\tools\test-order-mysql.ps1 -IncludeInventorySync
```

MySQL 专项创建回环临时实例及随机 schema。随后使用隔离的 Scheduler 和 Worker 验证完整派发链路。故障代理在订单事务已提交后丢弃第一次 HTTP 响应，原 Run 的第二次 Attempt 返回同一批次。新 Run 同步相同源版本时返回 `UNCHANGED`。应用版本 3 后收到迟到的版本 2 时返回 `STALE`，镜像保留版本 3 的数量。

## 2026-11-10 WMS 全量契约验证

环境为 Windows、Java 21.0.12.1、Spring Boot 4.1.1、H2 和回环隔离的 MySQL 8.0.34（REPEATABLE READ）。模拟 WMS 通过回环 HTTP 提供独立 Bearer 鉴权。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `.\mvnw.cmd -B -l .tools/wms-root-verify.log verify` | 七个服务模块成功，385 项通过，43 项条件跳过，0 失败或错误 | `.tools/wms-root-verify.log`，17:30:55 +08:00 完成 |
| `.\tools\test-order-mysql.ps1 -IncludeInventorySync` 中的 MySQL 测试 | 14 项通过，含 3 项模拟 WMS 集成测试，0 失败、错误或跳过 | `.tools/order-mysql/20261110-173056-116/tests.log`，17:31:32 +08:00 完成 |
| 同一命令的三进程故障验收 | 使用样例源通过 Scheduler、Worker 和订单服务验证提交后丢响应、同 Run 重放、同版本及旧版本保护 | `.tools/order-flow/20261110-173132-953/evidence.json` |

H2 和 MySQL 覆盖新版本、同版本重排及异内容、旧版本、跨来源隔离和旧请求重放。完整快照移除缺失项，显式零保留，显式空仓库移除全部镜像行。部分数据、非法数量、重复 SKU、仓库错配、鉴权失败和响应体超时均保留上一版。测试核对同步诊断、订单可售数量及预占数量。实际 WMS 的 TLS、鉴权协议、生产数据与长时间定时派发待部署联调。

## 2026-11-09 本机 RabbitMQ 验收

环境为 Windows、Java 21、本机 MySQL 订单库及已配置的 RabbitMQ 派发。Chrome 经 Nginx、Gateway 和 Keycloak 登录。任务 9「定期同步库存（样例仓库 12）」每 5 分钟执行，时区为 Asia/Shanghai，最多尝试 3 次。数据源为本机 JSON 样例。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `mvnw.cmd -B -o verify` | 根构建成功，362 项通过，27 项按条件跳过 | `.tools/inventory-sync-local-verify.log` |
| `tools/test-order-mysql.ps1 -IncludeInventorySync` | 隔离 MySQL 8.0.34 上 11 项通过。隔离派发另验证响应丢失后的同 Run 重试、同版本新 Run、更新版本及旧版本拒绝 | `.tools/order-mysql/20261109-175312-266/tests.log`、`.tools/order-flow/20261109-175334-779/evidence.json` |
| `node .tools/inventory-sync-local-acceptance.cjs` | 通过控制台模板创建停用任务，三个手动 Run 均成功。版本 1 写入 80 和 35 件，同版本重复写入 0 条。版本 2 应用后两个 SKU 分别为 81 和 35 件。启用后 18:00 的首个定时 Run 成功，版本 2 返回 `UNCHANGED` | `.tools/inventory-sync-local-evidence.json`，定时 Run `0394fd96-4e3b-424b-b0b0-c9d26505a8d7` |

此轮的本机 RabbitMQ 验收覆盖样例仓库正常派发。WMS 数据与 Broker 故障场景未纳入本轮本机验收。

## 2026-11-09 隔离环境首次验收

环境为 Windows、Java 21、H2 与回环隔离的 MySQL 8.0.34（REPEATABLE READ）。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `mvnw.cmd -B -o verify` | 全部模块构建成功，362 项通过，27 项按条件跳过 | `.tools/inventory-sync-root-verify.log` |
| `tools/test-order-mysql.ps1 -IncludeInventorySync` | 11 项通过，涵盖并发重复提交、HTTP 鉴权与旧版本保护 | `.tools/order-mysql/20261109-170812-850/tests.log` |
| 同一命令的三进程故障验收 | 已提交的 HTTP 响应被丢弃，第二次 Attempt 返回原批次。版本 2 晚到返回 STALE，镜像保持版本 3、数量 17 | `.tools/order-flow/20261109-170850-549/evidence.json` |
| 本机隔离预览执行 `node .tools/inventory-sync-browser.cjs e2e` | Chrome 经 Nginx、Gateway 与 Keycloak 登录，创建停用的模板任务并手动触发。一个 Attempt 成功，仓库 12 的 SKU 12001 和 12002 写入 80 与 35 件 | `.tools/inventory-sync-browser-evidence.json` |

验收使用样例源及隔离 HTTP 派发。真实 WMS 数据与正式实例的 RabbitMQ 联调未覆盖。

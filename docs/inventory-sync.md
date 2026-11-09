# 仓库库存快照同步

## 数据归属

我把库存同步放在独立订单服务的 inventory 包。第一版从受控 JSON 样例读取一个仓库的快照，将结果保存在订单库的 `inventory_sync_stock` 镜像中，供查询和对账。订单创建、付款、预占及释放使用原有 `sku_stock`、预占与流水。Scheduler 维护 Run 和 Attempt，Worker 通过内部 HTTP API 发起同步。订单服务 V3 迁移创建仓库版本、镜像和同步批次三张表。

内置样例是仓库 12、源版本 1、SKU 12001（80 件）与 12002（35 件）。可用 `TASKFLOW_INVENTORY_SYNC_SAMPLE_SOURCE` 指向本机 JSON，例如 `file:./.tools/inventory-snapshot.json`。每个新请求读取当前文件。更新样例时先写完整临时文件再替换，提供递增的正整数源版本。一次最多 1000 条 SKU，每个 SKU 唯一，数量范围为 0 到 10 亿。数量为 0 表示明确清零，快照缺少的 SKU 保留之前的数量和源版本。真实 WMS 接入需要提供同样的仓库和有序版本契约，并实现 `IInventorySnapshotSource`。

## 重试和并发

我在事务外读取库存来源，在短事务内锁仓库、比较版本，并一起提交镜像、仓库版本和批次。更高版本返回 `APPLIED`，相同版本返回 `UNCHANGED`，更低版本返回 `STALE`。后两种结果都成功记录批次，写入条数为零。一次结果提交成功后，即使 HTTP 响应丢失，相同请求键和仓库也返回原批次和原结果。相同键换仓库返回 409。

Worker 校验任务快照中的正整数 `warehouseId`，按 Run ID 派生请求键，在调用前检查租约与剩余执行预算。调用沿用订单服务连接超时、请求超时和内部令牌，cloud 按服务名发现，local 显式直连。HTTP POST 只发送一次，408、429、5xx 和传输失败由 Scheduler 按 Job 的 Attempt 上限重试。Run 摘要包含批次 ID、仓库、源版本、结果及写入条数。协议见 [API 文档](api.md#仓库库存同步-api-v1)。

## 本机接入

先准备订单服务的独立 MySQL schema 和账号。订单服务 `order.worker-token` 与 Worker `taskflow.order.token` 应使用同一个本机凭据。已有订单示例可以复用它的配置。先核对在途任务已结束，再构建并更新服务。

1. 在被 Git 忽略的 `config/taskflow-inventory-sync-local.yml` 写入以下配置。先重启订单服务完成 V3 迁移，再重启 Worker。

   ```yaml
   order:
     inventory-sync:
       enabled: true
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

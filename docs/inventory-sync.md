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

3. 在控制台新建「定期同步库存」，点击「刷新处理器列表」，用内置样例仓库 12 保存为停用。先手动运行，对照 Run 的 `batchId`、`sourceVersion` 和 `updatedCount`，通过内部查询接口核对 SKU 数量。首次同步预期 `APPLIED`，重复运行同一源版本预期 `UNCHANGED`。完成验证后再启用每 5 分钟的计划。

## 验证命令

```powershell
.\mvnw.cmd verify
.\tools\test-order-mysql.ps1 -IncludeInventorySync
```

MySQL 专项创建回环临时实例及随机 schema，使用隔离的 Scheduler 和 Worker，故障代理在订单事务已提交后丢弃第一次 HTTP 响应，检查原 Run 的第二次 Attempt 返回同一批次。另验证版本 3 应用后版本 2 晚到，镜像保留版本 3 的数量。

## 2026-11-09 验证记录

环境为 Windows、Java 21、H2 与回环隔离的 MySQL 8.0.34（REPEATABLE READ）。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `mvnw.cmd -B -o verify` | 全部模块构建成功，362 项通过，27 项按条件跳过 | `.tools/inventory-sync-root-verify.log` |
| `tools/test-order-mysql.ps1 -IncludeInventorySync` | 11 项通过，涵盖并发重复提交、HTTP 鉴权与旧版本保护 | `.tools/order-mysql/20261109-170812-850/tests.log` |
| 同一命令的三进程故障验收 | 已提交的 HTTP 响应被丢弃，第二次 Attempt 返回原批次。版本 2 晚到返回 STALE，镜像保持版本 3、数量 17 | `.tools/order-flow/20261109-170850-549/evidence.json` |
| 本机隔离预览执行 `node .tools/inventory-sync-browser.cjs e2e` | Chrome 经 Nginx、Gateway 与 Keycloak 登录，创建停用的模板任务并手动触发。一个 Attempt 成功，仓库 12 的 SKU 12001 和 12002 写入 80 与 35 件 | `.tools/inventory-sync-browser-evidence.json` |

验收使用样例源及隔离 HTTP 派发。真实 WMS 数据与正式实例的 RabbitMQ 联调未覆盖。

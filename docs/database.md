# Admin 任务定义数据库

Admin 的 job_definition 保存 Job 配置，V3 新增 job_sync_outbox 保存不可变配置事件与发送状态。Scheduler 在自己的 schema 保存 Run、Attempt、schedule_plan 与 schedule_sync_inbox；Worker 不直接访问这些表。

## 执行方式

当前后端已接入 Flyway，空库启动时自动建表。以下保留之前的手工 V1 建表步骤；如果准备启动后端，优先使用本页的 Flyway 接入步骤，不需要重复手工建表。

建表脚本要求 MySQL 8.0.16 或以上，使用 JSON 类型、毫秒时间和强制执行的 CHECK 约束。先执行 SELECT VERSION() 确认实际连接的服务端版本。

在 DataGrip 连接本地 MySQL 后，执行：

```sql
CREATE DATABASE IF NOT EXISTS taskflow_admin
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE taskflow_admin;
SET time_zone = '+00:00';
```

随后执行 [V1 建表脚本](../taskflow-admin/src/main/resources/db/migration/V1__create_job_definition.sql)。

脚本不包含 DROP 或修改已有数据的操作。CREATE TABLE 不使用 IF NOT EXISTS，避免同名旧表结构不一致却被当成创建成功；已有同名表时先查看 SHOW CREATE TABLE job_definition，不删除或覆盖原表。

完成后执行：

```sql
SHOW TABLES;
SHOW CREATE TABLE job_definition;
DESCRIBE job_definition;
```

## 字段与语义

| 字段 | 含义 |
| --- | --- |
| id | 数据库自增主键，任务的稳定标识 |
| name | 必填任务名称，允许不同任务同名 |
| description | 可选任务说明 |
| handler_key | 服务端注册的处理器标识，区分大小写 |
| payload_json | JSON 参数，无参数可为空；不保存密码等敏感值 |
| schedule_type | MANUAL 手动触发，CRON 定时执行 |
| cron_expression | 六字段 Cron；手动模式为 NULL，定时模式必填 |
| time_zone | Cron 的 IANA 时区，默认 Asia/Shanghai |
| enabled | 是否启用定时调度，默认停用；手动触发不受此字段控制 |
| timeout_seconds | 单次尝试的超时时间，默认 30 秒 |
| max_attempts | 包含首次执行的最大尝试次数，默认 3，即最多再重试 2 次 |
| retry_interval_seconds | 重试基础间隔，默认 5 秒；后续退避策略另行实现 |
| misfire_policy | 错过计划时 SKIP 跳过，或 FIRE_ONCE 补发一次 |
| config_version | 单个任务版本，默认 1；每次修改及启停都由应用显式递增 |
| created_at | 创建时间，UTC，毫秒精度 |
| updated_at | 最近修改时间，UTC，毫秒精度 |
| deleted_at | V2 新增，软删除时间；NULL 为未删除 |

V1 为 16 个字段，V2 增加 deleted_at 后为 17 个。数据库保存配置，不会因为建表或写入 Cron 就自动执行任务；Handler、调度、重试和超时由后续服务代码实现。

主键支持按 id 查询。V2 将原 (enabled, id) 索引替换为 (deleted_at, id) 和 (deleted_at, enabled, id)，分别支持过滤软删除后的列表及启停筛选。next_fire_time 属于 Scheduler 的计划副本，不属于 Admin。

CHECK 约束保证基本值域，Cron 语法、IANA 时区、参数结构与 Handler 白名单仍需由应用验证。当前页面的“最大重试次数”文案后续应调整为“最大尝试次数（含首次）”，与 max_attempts 保持一致。

配置更新使用条件更新，例如 WHERE id = ? AND config_version = ?，并在同一次更新中将 config_version 加 1；updated_at 自动更新不代表版本自动递增。单任务版本也不能当作跨任务的全局配置同步游标。

DATETIME 本身不携带时区。SET time_zone 只影响当前连接；后续 JDBC、迁移连接及手工写入都应使用 UTC。time_zone 字段只负责计划解析，可以为 Asia/Shanghai，与保存时间的 UTC 约定无冲突。

## 接入 Flyway 时的处理

Admin 已引入 MyBatis-Plus Spring Boot 4 Starter、MySQL 驱动和 Flyway，启动时执行所属 schema 的迁移。默认通过 localhost:3306、root 连接 taskflow_admin。主配置 application.yml 引入根目录被 Git 忽略的 config/taskflow-admin-local.yml，数据库配置位于 spring.datasource，Redis 配置位于 spring.data.redis；MyBatis 与分页参数也在该服务的 YAML 中配置。密码、地址和账号均可通过对应环境变量覆盖，实际凭据不打包进 JAR。MyBatis-Plus 底层仍通过 JDBC 驱动访问数据库，替换的是应用的手写 JDBC 仓储。详见 [持久层约定](persistence.md)。

本机已于 2026-10-26 验证 V1 结构并完成基线及 V2 迁移，后续正常启动即可，不需要重复执行建表、基线或 V2 脚本。

空库由 Flyway 执行 V1、V2、V3，不需要手工建表。V2 迁移脚本为 [V2__add_job_soft_delete.sql](../taskflow-admin/src/main/resources/db/migration/V2__add_job_soft_delete.sql)。已有 V2 库正常启动时自动执行 [V3__job_sync_outbox.sql](../taskflow-admin/src/main/resources/db/migration/V3__job_sync_outbox.sql)，新建 Outbox 并回填已有配置及删除墓碑；无需再次开启基线。

若已手工执行原 V1，先运行 SHOW CREATE TABLE job_definition，核对与 V1 的 16 个字段、类型、默认值、CHECK 和索引一致，并确认还没有 deleted_at 或 Flyway 历史表。再仅在首次启动设置 TASKFLOW_ADMIN_DB_BASELINE_ON_MIGRATE=true，建立版本 1 基线并自动执行 V2。默认不启用该选项。

成功后移除该环境变量，未来只让 Flyway 管理增量迁移，不修改已应用的 V1 / V2。若已经手工执行过 V2，需要单独核对并建立正确版本的基线，不能继续按版本 1 接管。

创建、修改、启停、删除 API 见 [接口文档](api.md)。delete 更新 deleted_at、enabled 和 config_version，保留物理行；暂不提供恢复删除接口。

## 验证状态

已在独立 MySQL 8.0.34 实例验证空库 V1→V2 迁移和原手工 V1→基线→V2 流程，并执行真实 HTTP 接口测试。本机 3306 上的 taskflow_admin 也已通过认证、核对 V1、建立基线并完成 V2 迁移；表中 17 个字段，任务记录仍为 0。当前 Admin 在 18080 上通过真实库查询验证。详细证据见 [后端验证记录](backend-verification.md)。

参考：[MySQL CREATE TABLE](https://dev.mysql.com/doc/refman/8.4/en/create-table.html)、[MySQL JSON 类型](https://dev.mysql.com/doc/refman/8.4/en/json.html)。

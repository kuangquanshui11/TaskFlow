# TaskFlow 任务管理后端验证

日期：2026-10-26。验证对象为 Admin 任务管理接口、数据库迁移和持久化；Scheduler / Worker 仍为独立启动骨架。

后续新增的任务详情缓存验证独立记录于 [缓存验证记录](cache-verification.md)，下方保留持久层迁移时的历史结果。

当前代码已调整为 Controller / Service / Mapper 结构，Service 接口与 impl 分开，最新目录和重构回归见 [结构调整记录](structure-verification.md)。

后续已加入三服务手动执行，最新构建、隔离端到端、本机迁移与界面验收见 [执行验证](execution-verification.md)；下方保留此前阶段的记录。

## MyBatis-Plus 迁移回归（最新）

Admin 已从手写 JDBC 仓储切换到 MyBatis-Plus 3.5.18 的 Boot 4 Starter，配置约定见 [持久层文档](persistence.md)。以下是迁移后的验证；后面的 20 项测试和 13 项 MySQL 测试属于此前阶段的记录。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| 根目录 mvnw.cmd verify | BUILD SUCCESS，24 项通过、1 项按环境条件跳过 | .tools/verification/mybatis-plus-root-verify.log |
| 独立 MySQL 8.0.34 的 JobApiTests | 16 项全部通过 | .tools/verification/mybatis-plus-mysql-tests.log |
| Flyway 空库迁移 | V1/V2 均 success=1 | 临时实例的 taskflow_it_mybatis.flyway_schema_history |
| 前端单元测试 | 6 项通过 | tools/test-frontend.ps1 输出 |
| Nginx → Admin → H2 浏览器测试 | 6 项通过 | taskflow-web/playwright-report、taskflow-web/test-results |
| 可执行 JAR 内容 | Admin 含新仓储与 MP 3.5.18，无旧 JdbcJobRepository；Scheduler 无 MP 依赖 | jar tf 检查 |

根构建中 JobApiTests 为 16 项，新增 JSON 嵌套与转义、可空字段清除、分页越界和空统计回归；另有 1 项数据库故障响应测试，使用关闭当前 H2 连接池模拟故障，确认返回 503 / DATABASE_UNAVAILABLE。该项发现并修正事务启动失败原先返回 500 的错误映射。其余为 Admin 3 项、Scheduler 2 项、Worker 2 项。唯一跳过项为需要显式开启的远程 Redis 只读连接测试，该阶段未访问远程 Redis。

真实 MySQL 使用独立临时数据目录和回环端口 13379，只操作新建的 taskflow_it_mybatis，测试后关闭实例；未对用户的 taskflow_admin 执行迁移或写入。16 项验证覆盖真实 JSON、NULL 更新、分页、统计、软删除、版本冲突及并发更新。浏览器测试启动 test,e2e YAML profile；日志确认使用 H2 后才执行可写场景，测试实例结束后清理。

测试连接参数从 application-test.yml、application-test-mysql.yml、application-e2e.yml 加载；MySQL 测试代码在选择 profile 和清理数据前分别验证隔离边界。测试保留 JdbcTemplate 独立核对实际数据库记录，业务请求则全部通过新的 MyBatis-Plus 仓储。

## 根项目构建

命令：

```powershell
.\mvnw.cmd -B -l target/backend-verify.log verify
```

结果：BUILD SUCCESS，20 项测试通过，无失败、错误或跳过。

| 测试 | 数量 | 覆盖内容 |
| --- | --- | --- |
| JobApiTests | 13 | 创建、JSON、查询筛选、分页、参数错误、版本冲突、并发修改、启停、软删除、统计、Cron 时区与日期边界、错误响应、请求 ID |
| TaskFlowAdminApplicationTests | 3 | Admin 健康检查、基础接口、演示页 |
| TaskFlowSchedulerApplicationTests | 2 | 独立启动、健康检查、管理资源隔离 |
| TaskFlowWorkerApplicationTests | 2 | 独立启动、健康检查、管理资源隔离 |

默认 Admin 测试使用仅在 test 作用域引入的 H2，使用专用测试表结构，测试不会访问用户的 MySQL。H2 测试不作为 MySQL JSON / CHECK / 迁移行为的依据。

## 真实 MySQL 接口验证

另启本机 MySQL 8.0.34 独立测试实例，监听 127.0.0.1:13379，使用项目 target 目录下的临时数据目录，不使用用户 3306 的实例、配置或数据目录。

在该实例创建 taskflow_it_jobs 空库，然后执行：

```powershell
$env:TASKFLOW_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:13379/taskflow_it_jobs?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
.\mvnw.cmd -B -l target/mysql-api-tests.log -pl taskflow-admin -Dtest=JobApiTests test
```

结果：BUILD SUCCESS，13 项真实 HTTP / MySQL 接口测试全部通过；Flyway V1、V2 成功迁移到版本 2。

此项验证实际覆盖 MySQL 原生 JSON 的读写、真实参数化 SQL、并发乐观锁更新和删除墓碑。测试执行前会清空专用库的任务记录，因此测试代码只接受 localhost / 127.0.0.1 和 taskflow_it_ 前缀的专用数据库 URL，拒绝 taskflow_admin。

若测试账号不是默认测试实例的 root，可使用 TASKFLOW_TEST_DB_USERNAME 和 TASKFLOW_TEST_DB_PASSWORD。不要把用户业务库作为测试库。

## 已有手工表的迁移与重启验证

在同一隔离实例创建 taskflow_it_existing，并仅手工执行原 V1 建表脚本。

1. 使用生产打包 JAR，显式设置 baseline-on-migrate=true。
2. 观察 Flyway 建立版本 1 基线并执行 V2。
3. 查询历史表得到 1 / BASELINE / success=1 和 2 / SQL / success=1。
4. 通过 POST /api/v1/jobs/insert 创建 restart-proof 任务，JSON 参数 batchSize=100。
5. 通过 selectById 查询成功，并在数据库确认任务已持久化。
6. 停止 Admin，再使用最终 JAR、关闭基线开关重启。
7. 观察 schema 已为版本 2，无重复迁移；健康检查 UP，原任务和 JSON 参数仍可查询。

测试结束后，Admin 与独立 MySQL 测试进程都已停止。临时测试数据和日志位于 target，受 .gitignore 排除。

## 本机数据库连接检查

验证连接为 127.0.0.1:3306/taskflow_admin，使用本机配置中的 root 凭据。

连接、迁移与只读接口检查结果如下：

1. 只读确认 MySQL 8.0.34、3306 和 taskflow_admin，原任务表与 V1 一致，记录数为 0。
2. 在该库建立版本 1 基线并执行 V2。历史表记录 1 / BASELINE / success=1、2 / SQL / success=1；任务表共 17 个字段，记录数仍为 0。
3. 正常 Maven 启动未开启基线开关，Flyway 确认当前版本 2，无重复迁移。
4. 8080 被已有 Java 进程占用，当前本机 Admin 改用 18080；原进程保留。
5. GET /actuator/health 返回 UP，GET /api/v1/jobs/select 和 /selectSummary 返回 OK，任务总数为 0。

凭证仅保存在根目录被 Git 忽略的本机配置，不包含在可执行 JAR 中；环境变量 TASKFLOW_ADMIN_DB_PASSWORD 可覆盖。本机配置统一使用 config/taskflow-admin-local.yml，数据库与 Redis 参数均以 YAML 管理。受版本管理的配置和构建日志中未发现凭证值。

接入本机配置后再次运行根目录 verify，20 项测试全部通过，日志位于 target/local-db-verify.log。Admin 使用正常 Maven 命令启动，并保持运行供本机调用。

## 尚未完成

- Cron 自动调度、动态 Worker 注册、心跳和跨节点故障恢复。
- 用户登录与权限模块。

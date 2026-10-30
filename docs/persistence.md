# TaskFlow MyBatis-Plus 持久层与配置约定

## 适用范围

Admin 使用 MyBatis-Plus 3.5.18 的 `mybatis-plus-spring-boot4-starter`，替换原 `NamedParameterJdbcTemplate` 业务仓储。Java 21、Spring Boot 4.1.1、现有 API 和 Flyway V1/V2 表结构保持兼容。Scheduler / Worker 已分别接入自己的 JDBC Mapper、MySQL 和 V1 Flyway 迁移，不引用 Admin 的 MyBatis Entity、Mapper 或数据表，见 [执行协议](execution-protocol.md)。

MyBatis-Plus 简化常规 CRUD 与分页；底层仍使用 JDBC 驱动和 HikariCP。并发正确性、执行幂等和服务数据隔离仍由业务设计保证。

## 分层与数据规则

```text
JobController / JobQueryController
              ↓
         IJobService
              ↓ implements
        JobServiceImpl
              ↓
         JobDataMapper
              ↓ implements
     MybatisJobDataMapper
              ↓
    JobMapper + JobEntity
              ↓
       Admin 自己的 MySQL
```

- Entity 位于 job/entity，SQL 接口 JobMapper 位于 job/mapper，数据访问实现 MybatisJobDataMapper 位于 job/mapper/mysql，分页插件配置位于 job/config。业务模型和接口 DTO 不添加 MyBatis 注解。
- MyBatis 扫描 job/mapper 时仅注册带 @Mapper 的 SQL 接口；JobDataMapper、JobCacheMapper 是普通数据访问接口，不由 MyBatis 创建代理，避免与 MySQL / Redis 实现 Bean 冲突。
- 常规插入、详情、筛选、分页通过 BaseMapper 和参数化条件构造器实现；统计保留单条聚合 SQL，显式排除已删除记录。
- `@TableId(AUTO)` 继续使用原数据库自增主键；数据库负责配置初始版本和创建时间。
- `@TableLogic` 使用 `deleted_at IS NULL` 表示未删除，自动过滤常规查询与更新。删除用单条带版本条件的更新，同时写删除时间、停用并递增版本，不物理删除。
- 修改、启停、删除统一显式比较 `id + config_version`，并在同条 SQL 内递增版本；受影响行数为零时沿用应用层 409/404 判断。不叠加自动乐观锁插件，避免版本条件和递增重复。
- 全量修改显式设置可空字段，允许清除说明、JSON 参数和 Cron；不能依赖默认忽略 null 的更新策略。
- 时间以 UTC 读写；Entity 的 LocalDateTime 在适配边界转换为领域 Instant。JSON 按原生 MySQL JSON 列保存，通过字符串映射并继续以 JSON 对象响应。
- 分页仍在应用层同一只读事务内查询总数和记录；关闭分页插件额外 COUNT，越界页返回空记录并保留真实总数。
- 禁用 MyBatis 会话查询复用和二级缓存。任务详情通过 IJobDetailService / JobDetailServiceImpl 调用 JobCacheMapper，实际 Redis 实现为 job/mapper/redis/RedisJobCacheMapper；未命中才进入上面的数据库路径。IJobService / JobServiceImpl 的详情读取保留为权威数据库读取，供写用例校验与返回新值。缓存语义见 [访问流程](request-flow.md)。

## 配置归属

| 配置 | 文件 |
| --- | --- |
| Java、Boot、MyBatis-Plus BOM 版本 | 根 pom.xml |
| Admin Starter、分页解析器、MySQL、Flyway 依赖 | taskflow-admin/pom.xml |
| 数据源、连接池、Redis、MyBatis、分页参数 | taskflow-admin/src/main/resources/application.yml |
| 实际本机数据库/Redis 参数与凭据 | config/taskflow-admin-local.yml，已被 Git 忽略 |
| 隔离 H2、SQL 初始化、测试 Redis 健康开关 | taskflow-admin/src/test/resources/application-test.yml |
| 隔离真实 MySQL 验证配置 | taskflow-admin/src/test/resources/application-test-mysql.yml |
| 模拟 Redis 的缓存集成验证 | taskflow-admin/src/test/resources/application-test-cache.yml |
| 远程 Redis 本机连接导入（先于 test 加载） | taskflow-admin/src/test/resources/application-test-redis-connection.yml |
| 真实 Redis 的随机测试 key 与短 TTL | taskflow-admin/src/test/resources/application-test-redis-cache.yml |
| 浏览器隔离 Admin 的 H2 与端口 | taskflow-admin/src/test/resources/application-e2e.yml |

不在 Java 代码中配置数据库地址、账号、密码、连接池大小或 Redis 连接参数。Java 配置类仅组装框架插件，从 YAML 读取数据库方言和分页上限。测试代码只选择测试 profile 并校验隔离库边界，连接参数也从 YAML / 环境变量读取。

## 验证方法

```powershell
# 默认只连接独立 H2；数据库故障测试仅关闭自己的 H2 连接池。
.\mvnw.cmd -pl taskflow-admin test
# 根目录结构、三个独立服务及可执行 JAR。
.\mvnw.cmd verify
# 前端通过隔离 Nginx → test,e2e profile 的 H2 Admin 验证。
.\tools\test-frontend.ps1
```

真实 MySQL 测试沿用 `TASKFLOW_TEST_DB_URL`、`TASKFLOW_TEST_DB_USERNAME`、`TASKFLOW_TEST_DB_PASSWORD`。JobApiTests 校验 URL 后启用 `test,test-mysql`，且在执行清理 SQL 前再次校验实际连接，只允许本机 `taskflow_it_` 前缀的专用数据库。禁止指向业务 taskflow_admin 库。H2 不替代真实 MySQL JSON 和 Flyway 验证。

## 官方参考

- [安装、Boot 4 Starter 与 BOM](https://baomidou.com/getting-started/install/)
- [分页插件与单独的解析器依赖](https://baomidou.com/plugins/pagination/)
- [逻辑删除与 LocalDateTime](https://baomidou.com/guides/logic-delete/)

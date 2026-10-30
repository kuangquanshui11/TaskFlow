# TaskFlow 项目协作与代码约定

## 架构原则

项目按微服务原则划分模块，以明确职责、独立维护和局部修改为目标。开发、重构、示例和文档统一遵守以下约定。

## 服务边界

- taskflow-admin：任务定义、管理 API、管理控制台；不承担调度扫描或 Handler 执行。
- taskflow-scheduler：调度计划副本、触发、Run/Attempt 权威状态、派发、重试和租约管理；不执行实际业务 Handler。
- taskflow-worker：Handler 注册和执行、执行资源控制、心跳和结果上报；不直接访问其他服务的数据表。
- 根 pom.xml 只承担聚合、版本和构建配置。三个服务分别具有启动类、配置、测试与可执行 JAR。
- 服务之间禁止通过 Maven 依赖引用对方的应用模块，也禁止共享 Entity、Repository、业务 Service 或跨服务扫描 Spring Bean。
- 服务协作通过版本化 HTTP API 或消息事件。出现真实复用需求后再引入独立协议模块，只允许协议 DTO/事件等稳定契约进入。
- 不因代码相似就引入 common 模块，不在公共模块中集中业务逻辑。

## 数据和一致性

- 浏览器访问链路：Nginx 静态直返 / API 转发 → Gateway（WebFlux/Netty）→ Admin 内嵌 Tomcat → 业务用例 → Redis 查询缓存 → 未命中查询本服务数据库并回填。详见 docs/request-flow.md。
- 基础设施模块 taskflow-gateway、taskflow-registry、taskflow-config-server 分别负责入口路由、Eureka 注册发现和集中配置，不承担调度业务、不访问业务数据库。cloud profile 使用服务名发现、OpenFeign/LoadBalancer、Resilience4j 和 Micrometer OTel；local/test 保留显式直连模式。Gateway 仅开放 /api/v1/** 与健康探针，不代理内部协议或注册/配置管理端点。
- 远程 POST 禁止自动重放；传输层重试与持久化 Outbox 业务重试分别管理。配置中心存放非秘密策略，本机凭据保存在忽略的 YAML 或环境变量；配置更新默认重启生效，不声称完整热刷新。详见 docs/microservice-platform.md。
- 数据库为权威来源。写入提交后使缓存失效；声明 TTL、缓存故障降级与非原子一致性边界，不通过缓存绕过服务数据归属。

- 每个服务拥有自己的数据和数据库迁移；同一本地 MySQL 实例可使用隔离的 schema 和账号。
- 不进行跨服务 SQL、联表、外键、共用 Repository 或跨服务本地事务。
- Admin 是 Job 配置的权威来源；Scheduler 维护带配置版本的计划副本，并拥有 Run/Attempt 的权威状态；Worker 的本地执行记录只服务于执行幂等与恢复。
- 跨服务调用声明超时、失败行为和幂等键；本地事务内不等待远程调用。
- 通过显式状态、有限重试、补偿/对账、Outbox 等机制处理跨服务失败，不把数据库提交和远程调用描述成原子操作。
- 区分 Job、Run、Attempt；自动重试复用 Run 并新增 Attempt，人工再次执行创建新的 Run。
- 执行以配置快照为准。终态更新检查状态与有效租约版本，不承诺任意外部副作用恰好执行一次。

## 服务内部组织

先按业务能力分包，每个业务模块使用 controller、service、mapper 结构，例如 job/controller、job/service、job/service/impl、job/mapper。业务能力内聚在同一模块，职责分层限制依赖方向，微服务边界与数据归属不变。

- controller：按业务功能拆分 Controller 类，声明请求路径、接收和校验前端参数、转换响应，并调用 Service 接口；不直接访问 Mapper、数据库或 Redis，不执行实际 Handler。
- service：统一存放 IxxxService 接口；service/impl 子目录放 XxxServiceImpl，通过 implements 实现对应接口，负责业务编排、校验、事务与 Mapper 调用。Controller 和其他 Service 注入接口。
- mapper：按需提供本服务 MySQL、Redis 等数据访问接口与实现；复杂实现可以继续按 mysql、redis 分子目录。Mapper 不反向调用 Controller 或 Service，不访问其他服务的数据。
- MyBatis 只扫描带 @Mapper 的 SQL 接口，不把 Redis 或其他数据访问接口注册成 SQL Mapper。
- dto：请求与响应对象；model：不依赖 Controller、SQL 或 Redis 实现的业务模型、状态和规则；entity：持久化实体；config：框架装配与配置；event、exception、filter 按实际需要创建。
- 不为没有数据访问需求的业务创建空 Mapper；尚无业务实现的服务不生成空目录或占位类。
- 尚未实现的功能不生成空 Controller、伪业务结果或大量占位类。
- 注释优先解释业务约束、设计原因和并发边界，避免复述代码。

## 实施和验证

- 项目使用本地 Git 管理版本。每次开始修改前检查 `git status` 和现有差异，保留用户未提交的修改，不混入无关提交。
- 每完成一个可验收的修改单元，执行适用验证后提交该单元的代码与文档；最终回复报告提交短哈希、修改内容和验证结果。未完成或未验证通过的工作不得描述成已验收。
- 提交前检查暂存差异，不提交本机凭据、忽略的配置、数据库文件、日志、构建产物或本机安装的 Agent 技能。未经用户明确授权，不推送远程、不强制推送、不重写已有历史。
- 回退已提交修改优先使用 `git revert` 产生新的回退提交，操作前检查工作区；不擅自使用 `git reset --hard`、`git clean` 或丢弃未提交修改。Git 回退只覆盖纳入版本管理的文件，不能代替数据库和本机配置备份。

- 接口路径与业务动作匹配：查询使用 /select，新增 /insert，修改 /update，删除 /delete，启停 /enable 和 /disable。查询使用 GET，状态变更使用 POST；具体路径和参数记录在 docs/api.md。

- 保留 Java 21 与当前 Spring Boot 版本；依赖按服务声明，通用版本由父 POM / Boot BOM 管理。
- Maven 依赖和版本写入根或服务 pom.xml；数据库、Redis、MyBatis 和连接池参数统一写入各服务 application*.yml，测试使用独立 YAML profile，不在业务 Java 或启动脚本中重复硬编码连接参数。实际凭据通过被忽略的本机 YAML 或环境变量提供，不提交密码、个人绝对路径或硬编码服务地址。
- 每次聚焦一个可验收能力，更新对应文档、API 约定和必要测试。
- 结构调整执行根目录 ./mvnw.cmd verify；修改服务行为执行对应模块测试并补充相关集成验证。
- 本地启动、端口和前置设施以 README.md 为准。
- 架构与分阶段计划分别见 docs/architecture.md、docs/development-plan.md。
- 架构以本文件和 docs/architecture.md 为准：服务独立运行、数据独立归属，不采用单应用、共享权威状态库或 APP_ROLE 切换角色的方案。

## 文档约定

- 设计文档以开发者第一人称叙述决策，说明结论、理由和适用边界；不记录对话指令、需求确认或逐轮交付过程。
- 验证记录使用明确日期、环境、命令、结果、证据和未验证范围，避免依赖对话上下文的时间指代；历史结果不得表述为当前重新验收。
- 部署文档说明前置条件、操作步骤和失败处理；工具可用性、协作过程及完成汇报不进入设计正文。

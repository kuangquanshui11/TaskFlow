# TaskFlow Registry

Eureka Server，默认回环地址 8761。为 Gateway/Admin/Scheduler/Worker 提供服务实例发现，不维护 Job、Run 或 Worker 业务会话，不依赖业务数据库和配置中心。

使用独立 Basic 凭据；默认空密码拒绝启动，密码至少 24 位。根目录 `tools/prepare-platform.ps1` 生成被忽略的本机配置，`tools/services.ps1 -Action start -Service registry` 启动。当前单实例，自我保护默认开启。

独立测试 `mvnw.cmd -pl taskflow-registry test`。更多说明见 [平台文档](../docs/microservice-platform.md)。

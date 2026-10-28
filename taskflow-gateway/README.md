# TaskFlow Gateway

WebFlux/Netty 公共入口，默认回环地址 8088。仅将 `/api/v1/**` 路由到 `lb://taskflow-admin`；内部 API、配置/注册及非健康管理端点不可代理。提供请求大小限制、关联 ID、负载均衡、Resilience4j 和 Micrometer OTel，不访问业务数据库。

默认 local 模式通过 SimpleDiscoveryClient 使用 `TASKFLOW_ADMIN_URL`（默认 localhost:8080）。cloud 模式强制从 Config Server 加载策略，通过 Eureka 选择 Admin；不使用 local 静态兜底。

从根目录执行 `tools/prepare-platform.ps1`、`tools/services.ps1 -Action start`；独立测试 `mvnw.cmd -pl taskflow-gateway test`。完整启动、凭据及验证见 [平台文档](../docs/microservice-platform.md)。

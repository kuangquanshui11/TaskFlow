# TaskFlow Config Server

集中配置服务，默认回环地址 8888、native profile，读取根目录 `deploy/config-repo` 的非秘密配置。可切换 git profile，通过环境变量提供独立仓库地址和凭据。仅允许认证读取；空密码拒绝启动，至少 24 位。

从根目录执行 `tools/prepare-platform.ps1` 和 `tools/services.ps1 -Action start -Service config-server`。Server 不使用自己的远程配置，不依赖 Eureka；应用配置默认启动加载，更新后重启生效，未开放热刷新端点。

独立测试 `mvnw.cmd -pl taskflow-config-server test`。配置选择、环境变量及失败语义见 [平台文档](../docs/microservice-platform.md)。

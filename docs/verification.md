# TaskFlow 第一步验证记录

验证日期：2026-10-26。范围为微服务工程骨架和现有管理台迁移，不包含数据库、任务调度或执行能力。

## 构建与自动化测试

在项目根目录运行：

```powershell
.\mvnw.cmd -B verify
```

结果：BUILD SUCCESS，根聚合项目和三个服务均成功。

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| taskflow-admin | 3 | 0 | 0 | 0 |
| taskflow-scheduler | 2 | 0 | 0 | 0 |
| taskflow-worker | 2 | 0 | 0 | 0 |

测试使用随机 HTTP 端口，验证三个服务各自的健康检查、Admin 基础接口和控制台，以及 Scheduler / Worker 不暴露管理台和 hello 接口。

测试报告位于各模块 target/surefire-reports；三个可执行 JAR 位于各模块 target 目录。target 是构建输出，不纳入版本管理。

## 独立进程验证

同时启动构建出的三个 JAR，测试覆盖端口为 18080、18081、18082，避免占用默认开发端口。三个独立终端分别运行：

```powershell
java -jar taskflow-admin/target/taskflow-admin-0.0.1-SNAPSHOT.jar --server.port=18080
```

```powershell
java -jar taskflow-scheduler/target/taskflow-scheduler-0.0.1-SNAPSHOT.jar --server.port=18081
```

```powershell
java -jar taskflow-worker/target/taskflow-worker-0.0.1-SNAPSHOT.jar --server.port=18082
```

| 验证项 | 观察结果 |
| --- | --- |
| 三个进程同时运行 | 三个端口的 /actuator/health 均返回 UP |
| Admin 控制台 | GET / 返回 200，包含 TaskFlow 页面内容 |
| Admin 基础接口 | GET /api/v1/hello 返回 200 |
| Scheduler / Worker 资源隔离 | /index.html 和 /api/v1/hello 均返回 404 |
| 停止 Worker | 向 Worker 所在终端发送 Ctrl+C，进程退出 |
| 其他服务独立性 | Worker 退出后，Admin / Scheduler 健康检查仍返回 UP |

验证完成后，Admin 和 Scheduler 也通过各自终端的 Ctrl+C 退出，三个测试进程均已停止。

## 验证边界

当前验证证明服务骨架可以独立构建、启动，管理端资源归属正确。尚未接入 MySQL、RabbitMQ、服务间调用或真实任务，因此不对任务持久化、消息可靠性、执行隔离或故障恢复作完成声明。

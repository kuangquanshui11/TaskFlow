package com.taskflow.worker.execution.service.impl;

import java.util.Set;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.execution.service.IDemoHandlerService;
import org.springframework.stereotype.Service;

@Service
public class DemoHandlerServiceImpl implements IDemoHandlerService {
    @Override public Set<String> keys() { return Set.of("demoSuccess", "demoFail", "demoSlow", "demoFailOnce"); }
    @Override public String execute(Snapshot snapshot, ExecutionContext context) throws InterruptedException {
        context.checkActive();
        return switch (snapshot.handlerKey()) {
            case "demoSuccess" -> "执行成功";
            case "demoFail" -> throw new IllegalStateException("示例处理器执行失败");
            case "demoFailOnce" -> {
                if (context.attemptNumber() == 1) throw new IllegalStateException("首次尝试失败，后续尝试可成功");
                yield "重试执行成功";
            }
            case "demoSlow" -> {
                var payload = snapshot.payload();
                long duration = payload != null && payload.has("durationMillis") ? payload.get("durationMillis").longValue() : 1000;
                if (duration < 0 || duration > 60000) throw new IllegalArgumentException("durationMillis 必须在 0–60000 之间");
                // 此处 sleep 只模拟耗时业务，派发/重试/恢复均由数据库状态驱动。
                Thread.sleep(duration);
                context.checkActive();
                yield "耗时示例执行完成";
            }
            default -> throw new IllegalArgumentException("处理器未注册");
        };
    }
}

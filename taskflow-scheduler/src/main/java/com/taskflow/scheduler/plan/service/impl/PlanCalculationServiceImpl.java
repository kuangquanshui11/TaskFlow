package com.taskflow.scheduler.plan.service.impl;

import java.time.*;
import java.time.temporal.ChronoUnit;
import com.taskflow.scheduler.plan.dto.PlanSyncRequest.Configuration;
import com.taskflow.scheduler.plan.model.PlanRecords.*;
import com.taskflow.scheduler.plan.service.IPlanCalculationService;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

@Service
public class PlanCalculationServiceImpl implements IPlanCalculationService {
    @Override
    public Calculation calculate(Configuration configuration, boolean deleted, Instant from) {
        if (configuration == null || !("MANUAL".equals(configuration.scheduleType()) || "CRON".equals(configuration.scheduleType())))
            throw new IllegalArgumentException("计划快照缺少有效的执行方式");
        Instant calculatedAt = from.truncatedTo(ChronoUnit.MILLIS);
        if (deleted) return new Calculation(null, calculatedAt, CalculationState.DELETED);
        if ("MANUAL".equals(configuration.scheduleType())) return new Calculation(null, calculatedAt, CalculationState.MANUAL);
        if (!configuration.enabled()) return new Calculation(null, calculatedAt, CalculationState.DISABLED);
        try {
            // 使用 ZonedDateTime 保留 DST 语义；next 严格晚于基准，不能把接受配置的瞬间视为到期触发。
            var next = CronExpression.parse(configuration.cronExpression()).next(from.atZone(ZoneId.of(configuration.timeZone())));
            if (next == null) return new Calculation(null, calculatedAt, CalculationState.NO_FUTURE_FIRE);
            var fire = next.toInstant();
            // 数据库存储为 UTC DATETIME(3)，不能把无法持久化的结果标成 READY。
            int utcYear = fire.atZone(ZoneOffset.UTC).getYear();
            if (utcYear < 1000 || utcYear > 9999) return new Calculation(null, calculatedAt, CalculationState.NO_FUTURE_FIRE);
            return new Calculation(fire, calculatedAt, CalculationState.READY);
        } catch (DateTimeException | ArithmeticException invalid) {
            throw new IllegalArgumentException("无法计算下一计划时间", invalid);
        }
    }
}

package com.taskflow.admin.job.service.impl;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.job.service.ISchedulePreviewService;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

@Service
public class SchedulePreviewServiceImpl implements ISchedulePreviewService {
    @Override
    public ZoneId validateTimeZone(String timeZone) {
        if (timeZone == null || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
            throw JobException.invalid("timeZone 必须是有效的 IANA 时区，例如 Asia/Shanghai");
        }
        return ZoneId.of(timeZone);
    }

    @Override
    public CronExpression validateCron(String expression) {
        try {
            if (expression == null || expression.isBlank() || expression.length() > 100) {
                throw JobException.invalid("定时任务需要填写不超过 100 字符的六字段 Cron");
            }
            return CronExpression.parse(expression);
        } catch (IllegalArgumentException ex) {
            throw JobException.invalid("cronExpression 不是有效的六字段 Cron 表达式");
        }
    }

    @Override
    public List<Instant> preview(String expression, String timeZone, Instant from, int count) {
        var zone = validateTimeZone(timeZone);
        var cron = validateCron(expression);
        var times = new ArrayList<Instant>(count);
        try {
            var cursor = from.atZone(zone);
            for (int index = 0; index < count; index++) {
                cursor = cron.next(cursor);
                if (cursor == null) {
                    throw JobException.invalid("Cron 在可计算的日期范围内没有下一次触发时间");
                }
                times.add(cursor.toInstant());
            }
        } catch (DateTimeException | ArithmeticException ex) {
            throw JobException.invalid("无法在给定日期范围内计算 Cron");
        }
        return List.copyOf(times);
    }
}

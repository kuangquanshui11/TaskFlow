package com.taskflow.admin.job.service.impl;

import java.nio.charset.StandardCharsets;

import com.taskflow.admin.job.event.JobDefinitionChanged;
import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import com.taskflow.admin.job.model.JobDefinition.ScheduleType;
import com.taskflow.admin.job.model.JobFilter;
import com.taskflow.admin.job.model.JobSummary;
import com.taskflow.admin.job.mapper.JobDataMapper;
import com.taskflow.admin.job.service.IHandlerService;
import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.job.service.ISchedulePreviewService;
import com.taskflow.admin.jobsync.service.IJobSyncService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobServiceImpl implements IJobService {
    private final JobDataMapper repository;
    private final IHandlerService handlers;
    private final ISchedulePreviewService schedules;
    private final ApplicationEventPublisher events;
    private final IJobSyncService sync;

    public JobServiceImpl(JobDataMapper repository, IHandlerService handlers,
                          ISchedulePreviewService schedules, ApplicationEventPublisher events, IJobSyncService sync) {
        this.repository = repository;
        this.handlers = handlers;
        this.schedules = schedules;
        this.events = events;
        this.sync = sync;
    }

    @Override
    @Transactional
    public JobDefinition insert(Configuration input) {
        var configuration = validate(input);
        var job = selectById(repository.insert(configuration));
        sync.enqueue(job, false);
        events.publishEvent(new JobDefinitionChanged(job.id()));
        return job;
    }

    @Override
    @Transactional(readOnly = true)
    public JobDefinition selectById(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new JobException(JobException.Kind.NOT_FOUND, "任务不存在或已删除"));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page select(JobFilter filter) {
        long total = repository.count(filter);
        return new Page(repository.select(filter), total, filter.page(), filter.pageSize());
    }

    @Override
    @Transactional(readOnly = true)
    public JobSummary selectSummary() {
        return repository.summarize();
    }

    @Override
    @Transactional
    public JobDefinition update(long id, long configVersion, Configuration input) {
        var configuration = validate(input);
        selectById(id);
        requireUpdated(repository.update(id, configVersion, configuration));
        var job = selectById(id);
        sync.enqueue(job, false);
        events.publishEvent(new JobDefinitionChanged(id));
        return job;
    }

    @Override
    @Transactional
    public JobDefinition changeEnabled(long id, long configVersion, boolean enabled) {
        selectById(id);
        requireUpdated(repository.changeEnabled(id, configVersion, enabled));
        var job = selectById(id);
        sync.enqueue(job, false);
        events.publishEvent(new JobDefinitionChanged(id));
        return job;
    }

    @Override
    @Transactional
    public void delete(long id, long configVersion) {
        var job = selectById(id);
        requireUpdated(repository.softDelete(id, configVersion));
        // 删除墓碑也携带最后一份快照，版本随软删除递增；接收端以 deleted 优先停用计划。
        sync.enqueue(new JobDefinition(id, job.configuration(), configVersion + 1, job.createdAt(), job.updatedAt()), true);
        events.publishEvent(new JobDefinitionChanged(id));
    }

    private void requireUpdated(boolean updated) {
        if (!updated) {
            throw new JobException(JobException.Kind.CONFLICT, "任务已被修改，请重新查询并使用最新 configVersion");
        }
    }

    private Configuration validate(Configuration input) {
        if (!handlers.select().contains(input.handlerKey())) {
            throw JobException.invalid("handlerKey 不在配置白名单中，请先查询可选处理器");
        }
        schedules.validateTimeZone(input.timeZone());
        if (input.scheduleType() == ScheduleType.CRON) {
            schedules.validateCron(input.cronExpression());
        } else if (input.cronExpression() != null) {
            throw JobException.invalid("手动任务的 cronExpression 必须为空");
        }
        if (input.payloadJson() != null
                && input.payloadJson().getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw JobException.invalid("payload 不能超过 64 KiB");
        }
        return input;
    }
}

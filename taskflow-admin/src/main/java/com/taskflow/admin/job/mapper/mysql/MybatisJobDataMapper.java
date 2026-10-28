package com.taskflow.admin.job.mapper.mysql;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import com.taskflow.admin.job.model.JobFilter;
import com.taskflow.admin.job.model.JobSummary;
import com.taskflow.admin.job.mapper.JobDataMapper;
import com.taskflow.admin.job.entity.JobEntity;
import com.taskflow.admin.job.mapper.JobMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisJobDataMapper implements JobDataMapper {
    private final JobMapper mapper;

    public MybatisJobDataMapper(JobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long insert(Configuration configuration) {
        var entity = JobEntity.from(configuration);
        mapper.insert(entity);
        if (entity.getId() == null) {
            throw new IllegalStateException("数据库未返回新任务 ID");
        }
        return entity.getId();
    }

    @Override
    public Optional<JobDefinition> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(JobEntity::toDomain);
    }

    @Override
    public List<JobDefinition> select(JobFilter filter) {
        // 应用用例已在同一事务内查询总数，这里只分页，避免重复 COUNT。
        var page = new Page<JobEntity>(filter.page(), filter.pageSize(), false);
        return mapper.selectPage(page, query(filter).orderByDesc(JobEntity::getId))
                .getRecords().stream().map(JobEntity::toDomain).toList();
    }

    @Override
    public long count(JobFilter filter) {
        return mapper.selectCount(query(filter));
    }

    @Override
    public JobSummary summarize() {
        var counts = mapper.summarize();
        return new JobSummary(counts.total(), counts.enabledCount(), counts.total() - counts.enabledCount(),
                counts.manualCount(), counts.total() - counts.manualCount());
    }

    @Override
    public boolean update(long id, long configVersion, Configuration configuration) {
        // 显式 set 保留替换语义：null 会清空原 description/payload/Cron，而非被默认更新策略忽略。
        var update = versionedUpdate(id, configVersion)
                .set(JobEntity::getName, configuration.name())
                .set(JobEntity::getDescription, configuration.description())
                .set(JobEntity::getHandlerKey, configuration.handlerKey())
                .set(JobEntity::getPayloadJson, configuration.payloadJson())
                .set(JobEntity::getScheduleType, configuration.scheduleType().name())
                .set(JobEntity::getCronExpression, configuration.cronExpression())
                .set(JobEntity::getTimeZone, configuration.timeZone())
                .set(JobEntity::getEnabled, configuration.enabled())
                .set(JobEntity::getTimeoutSeconds, configuration.timeoutSeconds())
                .set(JobEntity::getMaxAttempts, configuration.maxAttempts())
                .set(JobEntity::getRetryIntervalSeconds, configuration.retryIntervalSeconds())
                .set(JobEntity::getMisfirePolicy, configuration.misfirePolicy().name());
        return mapper.update(null, update) == 1;
    }

    @Override
    public boolean changeEnabled(long id, long configVersion, boolean enabled) {
        return mapper.update(null, versionedUpdate(id, configVersion)
                .set(JobEntity::getEnabled, enabled)) == 1;
    }

    @Override
    public boolean softDelete(long id, long configVersion) {
        // 删除必须同时停用并推进配置版本，不能直接使用不含版本条件的 deleteById。
        return mapper.update(null, versionedUpdate(id, configVersion)
                .set(JobEntity::getEnabled, false)
                .setSql("deleted_at = CURRENT_TIMESTAMP(3)")) == 1;
    }

    private LambdaUpdateWrapper<JobEntity> versionedUpdate(long id, long configVersion) {
        // 单条 SQL 比较并递增版本，统一保护修改、启停、删除；逻辑删除条件由 MP 自动追加。
        return Wrappers.<JobEntity>lambdaUpdate()
                .eq(JobEntity::getId, id)
                .eq(JobEntity::getConfigVersion, configVersion)
                .setSql("config_version = config_version + 1, updated_at = CURRENT_TIMESTAMP(3)");
    }

    private LambdaQueryWrapper<JobEntity> query(JobFilter filter) {
        var query = Wrappers.<JobEntity>lambdaQuery();
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            String keyword = filter.keyword().strip().replace("!", "!!")
                    .replace("%", "!%").replace("_", "!_");
            // SQL 片段为常量，用户值仍绑定参数；%/_/! 均按字面搜索。
            query.apply("name LIKE {0} ESCAPE '!'", "%" + keyword + "%");
        }
        if (filter.enabled() != null) {
            query.eq(JobEntity::getEnabled, filter.enabled());
        }
        if (filter.handlerKey() != null && !filter.handlerKey().isBlank()) {
            query.eq(JobEntity::getHandlerKey, filter.handlerKey().strip());
        }
        return query;
    }
}

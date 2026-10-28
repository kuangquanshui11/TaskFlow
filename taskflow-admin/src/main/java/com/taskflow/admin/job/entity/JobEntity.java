package com.taskflow.admin.job.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import com.taskflow.admin.job.model.JobDefinition.MisfirePolicy;
import com.taskflow.admin.job.model.JobDefinition.ScheduleType;

@TableName("job_definition")
public class JobEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String handlerKey;
    private String payloadJson;
    private String scheduleType;
    private String cronExpression;
    private String timeZone;
    private Boolean enabled;
    private Integer timeoutSeconds;
    private Integer maxAttempts;
    private Integer retryIntervalSeconds;
    private String misfirePolicy;
    private Long configVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "null", delval = "CURRENT_TIMESTAMP(3)")
    private LocalDateTime deletedAt;

    public static JobEntity from(Configuration configuration) {
        var entity = new JobEntity();
        entity.name = configuration.name();
        entity.description = configuration.description();
        entity.handlerKey = configuration.handlerKey();
        entity.payloadJson = configuration.payloadJson();
        entity.scheduleType = configuration.scheduleType().name();
        entity.cronExpression = configuration.cronExpression();
        entity.timeZone = configuration.timeZone();
        entity.enabled = configuration.enabled();
        entity.timeoutSeconds = configuration.timeoutSeconds();
        entity.maxAttempts = configuration.maxAttempts();
        entity.retryIntervalSeconds = configuration.retryIntervalSeconds();
        entity.misfirePolicy = configuration.misfirePolicy().name();
        return entity;
    }

    public JobDefinition toDomain() {
        var configuration = new Configuration(name, description, handlerKey, payloadJson,
                ScheduleType.valueOf(scheduleType), cronExpression, timeZone, enabled,
                timeoutSeconds, maxAttempts, retryIntervalSeconds, MisfirePolicy.valueOf(misfirePolicy));
        // DATETIME 不包含时区；数据库连接继续采用 UTC，与原仓储的时间语义一致。
        return new JobDefinition(id, configuration, configVersion,
                createdAt.toInstant(ZoneOffset.UTC), updatedAt.toInstant(ZoneOffset.UTC));
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getHandlerKey() { return handlerKey; }
    public void setHandlerKey(String handlerKey) { this.handlerKey = handlerKey; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public Integer getRetryIntervalSeconds() { return retryIntervalSeconds; }
    public void setRetryIntervalSeconds(Integer retryIntervalSeconds) { this.retryIntervalSeconds = retryIntervalSeconds; }
    public String getMisfirePolicy() { return misfirePolicy; }
    public void setMisfirePolicy(String misfirePolicy) { this.misfirePolicy = misfirePolicy; }
    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}

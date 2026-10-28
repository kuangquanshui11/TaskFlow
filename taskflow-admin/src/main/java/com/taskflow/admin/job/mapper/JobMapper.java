package com.taskflow.admin.job.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.admin.job.entity.JobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface JobMapper extends BaseMapper<JobEntity> {
    // 自定义 SQL 不会自动追加逻辑删除条件，统计必须显式排除墓碑。
    @Select("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END), 0) AS enabled_count,
                   COALESCE(SUM(CASE WHEN schedule_type = 'MANUAL' THEN 1 ELSE 0 END), 0) AS manual_count
            FROM job_definition WHERE deleted_at IS NULL
            """)
    Counts summarize();

    record Counts(long total, long enabledCount, long manualCount) {
    }
}

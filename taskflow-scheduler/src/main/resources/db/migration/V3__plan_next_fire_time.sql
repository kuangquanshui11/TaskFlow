ALTER TABLE schedule_plan ADD COLUMN next_fire_time DATETIME(3);
ALTER TABLE schedule_plan ADD COLUMN calculated_at DATETIME(3);
-- V2 副本由 Scheduler 使用自身时钟和时区规则补算，不在迁移 SQL 中实现 Cron。
ALTER TABLE schedule_plan ADD COLUMN calculation_state VARCHAR(24) NOT NULL DEFAULT 'PENDING';
CREATE INDEX idx_plan_calculation ON schedule_plan (calculation_state, job_id);
CREATE INDEX idx_plan_next_fire ON schedule_plan (calculation_state, next_fire_time, job_id);

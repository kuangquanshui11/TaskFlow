import { test } from 'node:test';
import assert from 'node:assert/strict';
import { intervalCron, dailyCron, readSchedule, scheduleDescription } from '../../src/lib/schedule-form.js';

test('分钟计划支持跨小时一致的间隔，60 分钟使用整点计划', () => {
  assert.equal(intervalCron(1), '0 * * * * *');
  assert.equal(intervalCron(10), '0 */10 * * * *');
  assert.equal(intervalCron(60), '0 0 * * * *');
  // */7 在小时交界只有 4 分钟间隔，不能作为均匀的每 7 分钟提供。
  for (const value of [0, 7, 61, 2.5, '', 'bad']) assert.equal(intervalCron(value), '');
});

test('每天执行时间按小时分钟生成，午夜和末分钟有效，非法或未填时间拒绝', () => {
  assert.equal(dailyCron('08:30'), '0 30 8 * * *');
  assert.equal(dailyCron('00:00'), '0 0 0 * * *');
  assert.equal(dailyCron('23:59'), '0 59 23 * * *');
  for (const value of ['', '24:00', '12:60', '8:30']) assert.equal(dailyCron(value), '');
});

test('编辑已有计划仅识别可精确表达的简单模式，复杂规则保留高级模式', () => {
  assert.deepEqual(readSchedule('0 */15 * * * *'), { mode: 'minutes', minutes: 15 });
  assert.deepEqual(readSchedule('0 0 * * * *'), { mode: 'minutes', minutes: 60 });
  assert.deepEqual(readSchedule('0 30 8 * * *'), { mode: 'daily', time: '08:30' });
  for (const cron of ['0 0 9 * * MON-FRI', '0 */7 * * * *', '5 * * * * *', '0 15 * * * *', '', null]) {
    assert.deepEqual(readSchedule(cron), { mode: 'cron' });
  }
});

test('计划说明跟随客户实际配置和手动模式，不使用模板默认时间', () => {
  assert.equal(scheduleDescription('0 */10 * * * *'), '每 10 分钟执行一次');
  assert.equal(scheduleDescription('0 30 8 * * *'), '每天 08:30 执行');
  assert.equal(scheduleDescription('', 'MANUAL'), '手动触发');
  assert.equal(scheduleDescription(''), '请选择执行时间');
  assert.equal(scheduleDescription(null), '请选择执行时间');
});

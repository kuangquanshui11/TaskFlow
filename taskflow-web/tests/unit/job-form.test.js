import { test } from 'node:test';
import assert from 'node:assert/strict';
import { emptyJob, jobToForm, validateJob, toJobPayload } from '../../src/lib/job-form.js';

test('编辑无说明和参数的数据库记录仍可提交完整配置', () => {
  const form = jobToForm({ name: '任务', handlerKey: 'demoSuccess', description: null, payload: null });
  const { errors, payload } = validateJob(form);
  assert.deepEqual(errors, {});
  assert.equal(toJobPayload(form, payload).description, null);
  assert.equal(payload, null);
});
test('手动任务提交时清除之前的 Cron，并保留失败策略', () => {
  const form = { ...emptyJob(), name: ' 手动任务 ', handlerKey: 'demoFail', scheduleType: 'MANUAL', maxAttempts: 4 };
  const body = toJobPayload(form, {});
  assert.equal(body.cronExpression, null);
  assert.equal(body.name, '手动任务');
  assert.equal(body.maxAttempts, 4);
});
test('拒绝损坏 JSON、数组和超过字节上限的参数', () => {
  const base = { ...emptyJob(), name: '任务', handlerKey: 'demoSuccess' };
  for (const payloadText of ['{bad}', '[]', '"text"', JSON.stringify({ text: '中'.repeat(23000) })]) {
    assert.ok(validateJob({ ...base, payloadText }).errors.payload);
  }
});
test('次数和秒数必须是范围内整数', () => {
  const result = validateJob({ ...emptyJob(), name: '任务', handlerKey: 'demoSuccess', timeoutSeconds: 0, maxAttempts: 11, retryIntervalSeconds: 1.5 });
  assert.ok(result.errors.timeoutSeconds);
  assert.ok(result.errors.maxAttempts);
  assert.ok(result.errors.retryIntervalSeconds);
});

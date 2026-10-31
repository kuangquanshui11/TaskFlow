import test from 'node:test';
import assert from 'node:assert/strict';
import { summaryWindow, formatSuccessRate, formatLatency, stateDistribution } from '../../src/lib/run-summary.js';

test('窗口使用绝对 UTC 时间，支持 24 小时和完整 7 天', () => {
  const now = new Date('2026-10-31T08:00:00+08:00');
  assert.deepEqual(summaryWindow(1, now), { from: '2026-10-30T00:00:00.000Z', to: '2026-10-31T00:00:00.000Z' });
  assert.equal(summaryWindow(7, now).from, '2026-10-24T00:00:00.000Z');
  assert.equal(now.toISOString(), '2026-10-31T00:00:00.000Z');
  assert.throws(() => summaryWindow(8, now), RangeError);
});
test('成功率缺省不伪装为零，实际零值与四位小数正确显示', () => {
  assert.equal(formatSuccessRate(null), '暂无数据'); assert.equal(formatSuccessRate(undefined), '暂无数据');
  assert.equal(formatSuccessRate(NaN), '暂无数据'); assert.equal(formatSuccessRate(Infinity), '暂无数据');
  assert.equal(formatSuccessRate(0), '0.00%'); assert.equal(formatSuccessRate(1), '100.00%');
  assert.equal(formatSuccessRate(0.8947), '89.47%');
});
test('耗时覆盖空值、零、毫秒、秒和分钟边界', () => {
  for (const value of [null, undefined, NaN, Infinity, -1]) assert.equal(formatLatency(value), '暂无数据');
  assert.equal(formatLatency(0), '0 ms'); assert.equal(formatLatency(999), '999 ms');
  assert.equal(formatLatency(1000), '1.00 秒'); assert.equal(formatLatency(4300), '4.30 秒');
  assert.equal(formatLatency(59999), '60.00 秒'); assert.equal(formatLatency(60000), '1.00 分钟');
});
test('状态分布补齐五项并保护空窗口', () => {
  const empty = stateDistribution(); assert.equal(empty.length, 5);
  assert.ok(empty.every(item => item.count === 0 && item.percent === 0));
  const states = stateDistribution({ SUCCEEDED: 3, FAILED: 1 }, 4);
  assert.equal(states.find(item => item.state === 'SUCCEEDED').percent, 75);
  assert.ok(stateDistribution({ SUCCEEDED: 1 }, 0).every(item => item.percent === 0));
});

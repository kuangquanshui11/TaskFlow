import test from 'node:test';
import assert from 'node:assert/strict';
import { buildRunQuery, emptyRunFilters, readRunLocation } from '../../src/lib/run-query.js';

test('failure links keep the exact statistics window, result group, and full task identifier', () => {
  const query = { from: '2026-11-01T06:30:00.123Z', to: '2026-11-08T06:30:00.123Z',
    jobId: '9223372036854775807', state: 'FAILED', resultCode: 'ERROR+X' };
  const { filters, applied } = readRunLocation(query);
  assert.deepEqual(applied, query);
  assert.equal(filters.range, 'custom');
  assert.deepEqual(buildRunQuery(filters, Date.now(), applied), query);
  assert.throws(() => readRunLocation({ ...query, to: null }), /链接无效/);
  assert.throws(() => readRunLocation({ ...query, from: '' }), /UTC 起止时间/);
  assert.throws(() => readRunLocation({ ...query, resultCode: 'x'.repeat(65) }), /64/);
});

test('relative windows freeze at submission and all filters intersect', () => {
  const query = buildRunQuery({ ...emptyRunFilters(), jobId: '9223372036854775807', state: 'FAILED', triggerType: 'CRON' }, Date.parse('2026-11-05T00:00:00Z'));
  assert.deepEqual(query, { jobId: '9223372036854775807', state: 'FAILED', triggerType: 'CRON', from: '2026-11-04T00:00:00.000Z', to: '2026-11-05T00:00:00.000Z' });
  const week = buildRunQuery({ ...emptyRunFilters(), range: '7d' });
  assert.equal(new Date(week.to) - new Date(week.from), 7 * 86400000);
});
test('historical UUID lookup omits time only when explicitly selected', () => {
  const runId = 'A1234567-1234-5678-90AB-123456789ABC';
  assert.deepEqual(buildRunQuery({ ...emptyRunFilters(), range: 'all', runId }), { runId: runId.toLowerCase() });
  assert.ok(buildRunQuery({ ...emptyRunFilters(), runId }).from);
  assert.throws(() => buildRunQuery({ ...emptyRunFilters(), range: 'all' }), /Run ID/);
});
test('rejects malformed identifiers enums and invalid custom windows', () => {
  for (const invalid of [{ runId: 'bad' }, { jobId: '1e3' }, { jobId: '0' }, { jobId: '9223372036854775808' }, { state: 'TIMED_OUT' }, { triggerType: 'HTTP' },
    { range: 'custom' }, { range: 'custom', from: '2026-11-02T00:00', to: '2026-11-01T00:00' },
    { range: 'custom', from: '2026-11-01T00:00', to: '2026-11-09T00:00' }]) {
    assert.throws(() => buildRunQuery({ ...emptyRunFilters(), ...invalid }));
  }
  const custom = buildRunQuery({ ...emptyRunFilters(), range: 'custom', from: '2026-11-01T00:00', to: '2026-11-02T00:00' });
  assert.equal(new Date(custom.from).getTime(), new Date('2026-11-01T00:00').getTime());
});

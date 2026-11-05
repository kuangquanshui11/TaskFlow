import test from 'node:test';
import assert from 'node:assert/strict';
import { buildRunQuery, emptyRunFilters } from '../../src/lib/run-query.js';

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

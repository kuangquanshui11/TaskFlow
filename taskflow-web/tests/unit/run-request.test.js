import test from 'node:test';
import assert from 'node:assert/strict';
import { beginRunRequest, clearPendingRun, readPendingRun } from '../../src/lib/run-request.js';
const storage = () => { const data = new Map(); return { getItem: (key) => data.get(key) || null, setItem: (key, value) => data.set(key, value), removeItem: (key) => data.delete(key) }; };
const uuid1 = '11111111-1111-4111-8111-111111111111';
const uuid2 = '22222222-2222-4222-8222-222222222222';

test('uncertain retry preserves request key and original version after a job changes', () => {
  const store = storage();
  const first = beginRunRequest({ id: 80001, configVersion: 1 }, store, () => uuid1);
  const retry = beginRunRequest({ id: 80001, configVersion: 2 }, store, () => uuid2);
  assert.deepEqual(retry, first);
  assert.equal(retry.configVersion, 1);
  clearPendingRun(80001, uuid1, store);
});
test('a confirmed submission allows a fresh manual run and an old response cannot clear it', () => {
  const store = storage();
  beginRunRequest({ id: 80002, configVersion: 1 }, store, () => uuid1);
  clearPendingRun(80002, uuid1, store);
  beginRunRequest({ id: 80002, configVersion: 2 }, store, () => uuid2);
  clearPendingRun(80002, uuid1, store);
  assert.equal(readPendingRun(80002, store).requestKey, uuid2);
  clearPendingRun(80002, uuid2, store);
});
test('storage failures retain in-memory idempotency', () => {
  const store = { getItem() { throw Error(); }, setItem() { throw Error(); }, removeItem() { throw Error(); } };
  const first = beginRunRequest({ id: 80003, configVersion: 1 }, store, () => uuid1);
  assert.deepEqual(beginRunRequest({ id: 80003, configVersion: 1 }, store, () => uuid2), first);
  clearPendingRun(80003, uuid1, store);
});

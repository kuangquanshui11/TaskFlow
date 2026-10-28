import { test } from 'node:test';
import assert from 'node:assert/strict';
import { configureSession } from '../../src/lib/session.js';
import { request, ApiError } from '../../src/api/client.js';
import { setRunRequestOwner, beginRunRequest, readPendingRun } from '../../src/lib/run-request.js';

test('每次请求携带当前令牌，401 清理会话且 POST 不重放', async () => {
  const previous = globalThis.fetch;
  let calls = 0; let rejected;
  configureSession({ getToken: async () => 'signed-token', onUnauthorized: (token) => { rejected = token; } });
  globalThis.fetch = async (path, options) => {
    calls++;
    assert.equal(options.headers.Authorization, 'Bearer signed-token');
    assert.equal(options.credentials, 'omit');
    return new Response(JSON.stringify({ code: 'UNAUTHENTICATED', message: '请重新登录' }), { status: 401 });
  };
  try {
    await assert.rejects(request('/api/v1/runs/insert', { method: 'POST', body: {} }), (error) => error instanceof ApiError && error.status === 401);
    assert.equal(calls, 1); assert.equal(rejected, 'signed-token');
  } finally {
    globalThis.fetch = previous;
    configureSession({ getToken: async () => null, onUnauthorized: () => {} });
  }
});
test('公开配置不携带令牌，禁止将令牌发送到外部地址', async () => {
  const previous = globalThis.fetch;
  configureSession({ getToken: async () => 'secret-token', onUnauthorized: () => {} });
  globalThis.fetch = async (path, options) => {
    assert.equal(options.headers.Authorization, undefined);
    return new Response(JSON.stringify({ code: 'OK', data: {} }));
  };
  try {
    await request('/api/v1/access/selectConfiguration', { auth: false });
    await assert.rejects(request('https://other.example/api/v1/jobs/select'), { message: 'API 路径不合法。' });
  } finally {
    globalThis.fetch = previous;
    configureSession({ getToken: async () => null, onUnauthorized: () => {} });
  }
});
test('切换账号后不会沿用另一账号未确认的运行请求', () => {
  const values = new Map();
  const storage = { getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value) };
  setRunRequestOwner('alice');
  const request = beginRunRequest({ id: 3, configVersion: 1 }, storage, () => '00000000-0000-4000-8000-000000000003');
  setRunRequestOwner('bob');
  assert.equal(readPendingRun(3, storage), null);
  setRunRequestOwner('alice');
  assert.deepEqual(readPendingRun(3, storage), request);
  setRunRequestOwner('');
});

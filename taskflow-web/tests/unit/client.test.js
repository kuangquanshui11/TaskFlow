import { test } from 'node:test';
import assert from 'node:assert/strict';
import { request, ApiError } from '../../src/api/client.js';

test('保留后端版本冲突的状态与追踪信息，不自动重试 POST', async () => {
  const previous = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; return new Response(JSON.stringify({ code: 'CONFLICT', message: '版本冲突', data: null, requestId: 'r-1' }), { status: 409 }); };
  try {
    await assert.rejects(request('/api/v1/jobs/update', { method: 'POST', body: {} }), (error) => error instanceof ApiError && error.status === 409 && error.requestId === 'r-1');
    assert.equal(calls, 1);
  } finally { globalThis.fetch = previous; }
});
test('Nginx HTML 错误页转换为可展示错误，主动取消保持 AbortError', async () => {
  const previous = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response('<html>Bad gateway</html>', { status: 502 });
    await assert.rejects(request('/api/v1/jobs/select'), (error) => error.status === 502 && error.message.includes('连接'));
    const controller = new AbortController(); controller.abort();
    globalThis.fetch = async () => { throw new DOMException('aborted', 'AbortError'); };
    await assert.rejects(request('/api/v1/jobs/select', { signal: controller.signal }), { name: 'AbortError' });
  } finally { globalThis.fetch = previous; }
});

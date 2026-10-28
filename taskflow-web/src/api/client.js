export class ApiError extends Error {
  constructor(message, { code, status, details, requestId } = {}) {
    super(message);
    this.name = 'ApiError';
    Object.assign(this, { code, status, details, requestId });
  }
}

// API 地址始终同源；实际服务地址仅由 Nginx 或开发代理配置。
export async function request(path, { method = 'GET', body, signal } = {}) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(abort, 12000);
  try {
    const response = await fetch(path, {
      method,
      signal: controller.signal,
      cache: 'no-store',
      headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    let result;
    try { result = await response.json(); } catch {
      throw new ApiError(response.status >= 500 ? '服务暂时无法连接，请稍后重试。' : '服务返回了无法识别的响应。', { status: response.status });
    }
    if (!response.ok || result.code !== 'OK') {
      throw new ApiError(result.message || '请求失败，请稍后重试。', {
        status: response.status, code: result.code, details: result.data, requestId: result.requestId,
      });
    }
    return result.data;
  } catch (error) {
    if (signal?.aborted) throw new DOMException('Request aborted', 'AbortError');
    if (error instanceof ApiError) throw error;
    throw new ApiError(error.name === 'AbortError' ? '请求超时，请稍后重试。' : '暂时无法连接服务，请检查连接后重试。');
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}

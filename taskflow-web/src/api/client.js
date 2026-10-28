import { accessToken, sessionRejected } from '../lib/session.js';

export class ApiError extends Error {
  constructor(message, { code, status, details, requestId } = {}) {
    super(message);
    this.name = 'ApiError';
    Object.assign(this, { code, status, details, requestId });
  }
}

// API 地址始终同源；实际服务地址仅由 Nginx 或开发代理配置。
export async function request(path, { method = 'GET', body, signal, auth = true, bearerToken } = {}) {
  if (!path.startsWith('/api/v1/') || path.includes('\\')) throw new ApiError('API 路径不合法。');
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(abort, 12000);
  try {
    const token = bearerToken ?? (auth ? await accessToken() : null);
    const response = await fetch(path, {
      method,
      signal: controller.signal,
      cache: 'no-store',
      credentials: 'omit',
      headers: { Accept: 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (response.status === 401 && auth) sessionRejected(token);
    let result;
    try { result = await response.json(); } catch {
      const message = response.status === 401 ? '请重新登录。' : response.status === 403 ? '当前账号没有此操作权限。'
        : response.status === 429 ? '请求过于频繁，请稍后重试。' : response.status >= 500 ? '服务暂时无法连接，请稍后重试。' : '服务返回了无法识别的响应。';
      throw new ApiError(message, { status: response.status });
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
    if (error.name === 'SessionExpiredError') throw new ApiError(error.message, { status: 401, code: 'UNAUTHENTICATED' });
    throw new ApiError(error.name === 'AbortError' ? '请求超时，请稍后重试。' : '暂时无法连接服务，请检查连接后重试。');
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}

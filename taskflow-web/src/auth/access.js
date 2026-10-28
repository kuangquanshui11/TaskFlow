import { reactive } from 'vue';
import Keycloak from 'keycloak-js';
import { request } from '../api/client.js';
import { configureSession } from '../lib/session.js';
import { setRunRequestOwner } from '../lib/run-request.js';

export const access = reactive({ loading: true, busy: false, error: '', user: null });
let keycloak;
let initializing;
let generation = 0;
let registeredToken;
let tokenPending;

export const can = (permission) => Boolean(access.user?.permissions.includes(permission));

function clearSession(message = '') {
  generation++;
  keycloak?.clearToken();
  access.user = null;
  registeredToken = undefined;
  access.error = message;
  setRunRequestOwner('');
}
function expired() {
  clearSession('登录已失效，请重新登录。');
  const error = new Error('登录已失效，请重新登录。');
  error.name = 'SessionExpiredError';
  return error;
}
configureSession({
  async getToken() {
    if (!keycloak?.authenticated) return null;
    if (tokenPending) return tokenPending;
    tokenPending = (async () => {
      try {
        await keycloak.updateToken(30);
        if (!registeredToken) throw expired();
        const token = keycloak.token;
        if (token !== registeredToken) {
          await request('/api/v1/access/update', { method: 'POST', body: { previousToken: registeredToken }, bearerToken: token });
          registeredToken = token;
        }
        return token;
      } catch (failure) {
        if (failure.status === 503) throw failure;
        throw expired();
      } finally { tokenPending = undefined; }
    })();
    return tokenPending;
  },
  onUnauthorized(token) {
    if (token && token === keycloak?.token) clearSession('登录已失效，请重新登录。');
  },
});

export function initializeAccess() {
  if (initializing) return initializing;
  const current = ++generation;
  access.loading = true; access.error = '';
  initializing = (async () => {
    try {
      const config = await request('/api/v1/access/selectConfiguration', { auth: false });
      const marker = config.issuer.lastIndexOf('/realms/');
      if (marker < 0) throw new Error('登录服务配置不完整，请联系管理员。');
      keycloak = new Keycloak({ url: config.issuer.slice(0, marker), realm: decodeURIComponent(config.issuer.slice(marker + 8)), clientId: config.clientId });
      const authenticated = await keycloak.init({ onLoad: 'check-sso', pkceMethod: 'S256', checkLoginIframe: false, responseMode: 'query', scope: 'openid profile' });
      if (authenticated) {
        await request('/api/v1/access/insert', { method: 'POST', bearerToken: keycloak.token });
        registeredToken = keycloak.token;
        const user = await request('/api/v1/access/selectCurrent');
        if (current !== generation) return;
        access.user = user;
        setRunRequestOwner(user.subject);
      }
    } catch (failure) {
      if (current === generation) {
        keycloak?.clearToken();
        access.user = null;
        registeredToken = undefined;
        access.error = failure.message || '暂时无法连接登录服务，请稍后重试。';
      }
    } finally { access.loading = false; initializing = null; }
  })();
  return initializing;
}
export async function login() {
  if (access.busy) return;
  access.busy = true; access.error = '';
  try {
    if (!keycloak) await initializeAccess();
    if (!keycloak) throw new Error('登录服务暂时不可用，请稍后重试。');
    await keycloak.login({ redirectUri: location.origin + location.pathname, prompt: 'login', maxAge: 0 });
  } catch { access.error = '暂时无法打开登录服务，请稍后重试。'; }
  finally { access.busy = false; }
}
export async function logout() {
  const adapter = keycloak;
  const logoutUrl = adapter?.createLogoutUrl({ redirectUri: location.origin + '/' });
  access.user = null;
  access.busy = true;
  try {
    if (registeredToken) await request('/api/v1/access/disable', { method: 'POST' });
  } catch { /* Local access is removed even when the session store is unavailable. */ }
  // Remove local access immediately, even when the identity provider cannot complete logout.
  clearSession();
  access.busy = false;
  try { if (logoutUrl) location.assign(logoutUrl); }
  catch { access.error = '已退出本控制台，统一登录会话暂时无法注销，请关闭浏览器或稍后重试。'; }
}

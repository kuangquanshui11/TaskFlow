import { writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { login, readSettings, sessionKey } from './execution-identity.mjs';

const [phase, settingsPath, tokensPath, evidenceDir, jobId] = process.argv.slice(2);
const settings = readSettings(settingsPath);
const administrator = readSettings(tokensPath).access_token;
const gateway = 'http://127.0.0.1:19088';
const admin = 'http://127.0.0.1:19080';
const checks = [];
function check(value, name) {
  if (!value) throw Error(`Security acceptance failed: ${name}`);
  checks.push({ name, result: 'PASS' });
  console.log('PASS ' + name);
}
function redis(...args) {
  const response = spawnSync(settings.redisCli, ['--raw', '-h', '127.0.0.1', '-p', String(settings.sessionRedis.port), ...args], {
    env: { ...process.env, REDISCLI_AUTH: settings.sessionRedis.password }, encoding: 'utf8', windowsHide: true, timeout: 5000,
  });
  if (response.error || response.status !== 0 || /^(ERR|NOAUTH)/.test(response.stdout)) throw Error('Isolated Redis command failed');
  return response.stdout.trim();
}
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function api(base, path, token, body, extra = {}) {
  await pause(220);
  const response = await fetch(base + '/api/v1/' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), 'Content-Type': 'application/json', ...extra },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(7000),
  });
  return { status: response.status, body: await response.json() };
}
try {
  if (phase === 'security') {
    for (const base of [gateway, admin]) {
      check((await api(base, 'jobs/selectSummary')).status === 401, 'Anonymous API rejected at ' + base);
      check((await api(base, 'jobs/selectSummary', administrator.slice(0, -10) + 'invalid000')).status === 401, 'Invalid signature rejected at ' + base);
    }
    const viewer = await login(settings, true);
    const token = viewer.access_token;
    check((await api(gateway, 'jobs/selectSummary', token)).status === 401, 'Valid JWT alone cannot bypass Redis session admission');
    check((await api(gateway, 'access/insert', token, {})).status === 200, 'VIEWER establishes an interactive session');
    for (const base of [gateway, admin]) {
      check((await api(base, 'jobs/selectSummary', token)).status === 200, 'VIEWER reads through ' + base);
      for (const path of ['jobs/insert', 'jobs/update', 'jobs/delete', 'jobs/enable', 'runs/insert', 'job-sync/updateRetry']) {
        check((await api(base, path, token, {}, { 'X-Taskflow-Roles': 'ADMIN' })).status === 403, 'VIEWER denied ' + path + ' at ' + base);
      }
    }
    const key = sessionKey(settings, token);
    check(redis('HGET', key, 'current') === token, 'Real Redis stores the authenticated JWT');
    check(Number(redis('PTTL', key)) > 1790000, 'Session has the configured thirty-minute TTL');
    redis('PEXPIRE', key, '15000');
    check((await api(gateway, 'jobs/selectSummary', token)).status === 200 && Number(redis('PTTL', key)) > 1790000,
      'Authenticated request renews the real Redis TTL to thirty minutes');
    const refreshResponse = await fetch(settings.issuer + '/protocol/openid-connect/token', { method: 'POST',
      body: new URLSearchParams({ grant_type: 'refresh_token', client_id: 'taskflow-console', refresh_token: viewer.refresh_token }) });
    check(refreshResponse.ok, 'Keycloak refreshes a real access token');
    const refreshed = await refreshResponse.json();
    check((await api(gateway, 'access/update', refreshed.access_token, { previousToken: token })).status === 200, 'Refresh rotates the active Redis session');
    check((await api(gateway, 'jobs/selectSummary', token)).status === 200, 'In-flight previous JWT works during rotation grace');
    check((await api(gateway, 'access/disable', refreshed.access_token, {})).status === 200, 'Logout revokes the Redis session');
    for (const rejected of [token, refreshed.access_token]) {
      check((await api(gateway, 'jobs/selectSummary', rejected)).status === 401, 'Logout rejects current and previous JWTs');
      check((await api(gateway, 'access/insert', rejected, {})).status === 401, 'Logged-out login token cannot recreate its session');
    }
    const active = await login(settings, true);
    check((await api(gateway, 'access/insert', active.access_token, {})).status === 200, 'Fresh password login after logout succeeds');
    const expiredKey = sessionKey(settings, active.access_token);
    // Accelerate one known test session only; retain the application's thirty-minute idle timeout.
    redis('PEXPIRE', expiredKey, '100');
    await pause(6100);
    check(redis('EXISTS', expiredKey) === '0', 'Real Redis expires the selected session');
    const expired = await api(gateway, 'jobs/selectSummary', active.access_token);
    check(expired.status === 401 && expired.body.code === 'SESSION_EXPIRED', 'Expired session rejects a still-valid signed JWT');
    check((await api(gateway, 'access/insert', active.access_token, {})).status === 401, 'Old SSO authentication cannot recreate expired session');
    check((await api(gateway, 'access/update', active.access_token, { previousToken: active.access_token })).status === 401,
      'Token rotation cannot recreate expired session');
    const again = await login(settings, true);
    check((await api(gateway, 'access/insert', again.access_token, {})).status === 200, 'Fresh password authentication restores access after expiry');
    check((await api(gateway, 'jobs/selectSummary', again.access_token)).status === 200, 'Reauthenticated VIEWER can query results');
    await api(gateway, 'access/disable', again.access_token, {});
  } else if (['session-redis', 'rate-redis'].includes(phase)) {
    const direct = await api(admin, 'jobs/selectSummary', administrator);
    check(direct.status === (phase === 'session-redis' ? 503 : 200), 'Redis fault is isolated to its owning service');
    if (phase === 'session-redis') check(direct.body.code === 'SESSION_UNAVAILABLE', 'Admin fails closed when session Redis is unavailable');
    const requestKey = randomUUID();
    const rejected = await api(gateway, 'runs/insert', administrator, { jobId: Number(jobId), configVersion: 1, requestKey });
    check(rejected.status === 503, 'Redis outage rejects Run creation through Gateway');
    if (phase === 'rate-redis') {
      check(rejected.body.code === 'RATE_LIMIT_UNAVAILABLE', 'Gateway rejects Redis failure sentinel instead of forwarding');
      check((await api(admin, 'runs/selectByRequestKey?requestKey=' + requestKey, administrator)).status === 404,
        'Failed rate-limit request never creates a Run');
    }
  } else { throw Error('Unknown security phase'); }
  writeFileSync(`${evidenceDir}/security-${phase}.json`, JSON.stringify(checks, null, 2));
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}

import { readFileSync, writeFileSync } from 'node:fs';
import { randomBytes, createHash } from 'node:crypto';
import { chromium } from '../../taskflow-web/node_modules/@playwright/test/index.mjs';

export const readSettings = path => JSON.parse(readFileSync(path, 'utf8').replace(/^\uFEFF/, ''));
export const claims = token => JSON.parse(Buffer.from(token.split('.')[1], 'base64url'));
export function sessionKey(settings, token) {
  const jwt = claims(token);
  return settings.sessionPrefix + createHash('sha256').update(`${jwt.iss}\0${jwt.sub}\0${jwt.sid || jwt.session_state}`).digest('hex');
}

// A real public-client Authorization Code + PKCE login. No password grant or signed test token.
export async function login(settings, viewer = false) {
  const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL || 'chrome' });
  try {
    const page = await browser.newPage();
    const verifier = randomBytes(32).toString('base64url');
    const state = randomBytes(24).toString('hex');
    const redirect = settings.baseUrl + '/';
    await page.route(redirect + '?**', route => route.fulfill({ body: 'Test callback received' }));
    const url = new URL(settings.issuer + '/protocol/openid-connect/auth');
    url.search = new URLSearchParams({ client_id: 'taskflow-console', redirect_uri: redirect,
      response_type: 'code', scope: 'openid', prompt: 'login', max_age: '0', state,
      code_challenge_method: 'S256', code_challenge: createHash('sha256').update(verifier).digest('base64url') });
    await page.goto(url.href);
    await page.locator('#username').fill(viewer ? settings.viewerUsername : settings.username);
    await page.locator('#password').fill(viewer ? settings.viewerPassword : settings.password);
    const callback = page.waitForRequest(request => request.url().startsWith(redirect + '?'));
    await page.locator('#kc-login').click();
    const received = new URL((await callback).url());
    if (received.searchParams.get('state') !== state || !received.searchParams.get('code')) throw Error('Invalid OIDC callback');
    const response = await fetch(settings.issuer + '/protocol/openid-connect/token', { method: 'POST',
      body: new URLSearchParams({ grant_type: 'authorization_code', client_id: 'taskflow-console',
        redirect_uri: redirect, code: received.searchParams.get('code'), code_verifier: verifier }) });
    if (!response.ok) throw Error(`OIDC code exchange HTTP ${response.status}`);
    const tokens = await response.json();
    const jwt = claims(tokens.access_token);
    if (jwt.iss !== settings.issuer || !jwt.sub || !jwt.sid || !jwt.auth_time) throw Error('Missing interactive identity claims');
    return tokens;
  } finally { await browser.close(); }
}

if (process.argv[1]?.endsWith('execution-identity.mjs')) {
  const [action, settingsPath, output] = process.argv.slice(2);
  const settings = readSettings(settingsPath);
  if (action === 'prepare') {
    const realm = readSettings('deploy/access/taskflow-realm.json');
    realm.realm = settings.realm;
    const client = realm.clients.find(client => client.clientId === 'taskflow-console');
    client.redirectUris = [settings.baseUrl + '/*'];
    client.webOrigins = [settings.baseUrl];
    client.attributes['post.logout.redirect.uris'] = settings.baseUrl + '/';
    realm.users = [[settings.username, settings.password, 'ADMIN'], [settings.viewerUsername, settings.viewerPassword, 'VIEWER']]
      .map(([username, password, role]) => ({ username, enabled: true, firstName: 'Acceptance', lastName: role,
        email: username + '@example.test', emailVerified: true, credentials: [{ type: 'password', value: password, temporary: false }],
        clientRoles: { 'taskflow-api': [role] } }));
    writeFileSync(output, JSON.stringify(realm, null, 2));
  } else if (action === 'login') {
    writeFileSync(output, JSON.stringify(await login(settings)));
  } else { throw Error('Unknown identity command'); }
}

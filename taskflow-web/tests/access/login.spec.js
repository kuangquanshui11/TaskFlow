import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { sessionKey } from '../../../tools/local/execution-identity.mjs';

const settingsPath = process.env.TASKFLOW_ACCESS_SETTINGS;
test.skip(!settingsPath, '需要指向隔离 Keycloak 随机测试账号配置的 TASKFLOW_ACCESS_SETTINGS');
const settings = settingsPath ? JSON.parse(readFileSync(settingsPath, 'utf8').replace(/^\uFEFF/, '')) : {};
const evidence = process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR || '../.tools/access-browser';
mkdirSync(evidence, { recursive: true });
let job;
let administratorToken;
async function login(page, viewer = false) {
  await page.goto('/jobs');
  await page.getByRole('button', { name: '登录 TaskFlow', exact: true }).click();
  await page.locator('#username').fill(viewer ? settings.viewerUsername : settings.username);
  await page.locator('#password').fill(viewer ? settings.viewerPassword : settings.password);
  const current = page.waitForRequest((request) => request.url().includes('/api/v1/access/selectCurrent'));
  await page.locator('#kc-login').click();
  const authorization = (await current).headers().authorization;
  await expect(page.locator('.signed-in-user')).toHaveText(viewer ? settings.viewerUsername : settings.username);
  return authorization;
}

test('前端登录页、真实 Keycloak 登录、JWT 访问、新建任务和退出', async ({ page, request }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto('/jobs');
  await expect(page.getByRole('heading', { name: '登录你的工作空间' })).toBeVisible();
  await expect(page.getByRole('button', { name: '登录 TaskFlow', exact: true })).toBeEnabled();
  await page.screenshot({ path: evidence + '/login-desktop.png' });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.screenshot({ path: evidence + '/login-mobile.png' });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.setViewportSize({ width: 1440, height: 1000 });
  administratorToken = await login(page);
  expect(administratorToken).toMatch(/^Bearer [^.]+\.[^.]+\.[^.]+$/);
  expect((await request.get('/api/v1/jobs/selectSummary')).status()).toBe(401);
  await page.getByRole('button', { name: '新建任务', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '创建任务', exact: true });
  await dialog.getByLabel('任务名称').fill('登录验收-' + Date.now());
  await dialog.getByLabel('处理器').selectOption('demoSuccess');
  const created = page.waitForResponse(response => response.url().endsWith('/api/v1/jobs/insert') && response.request().method() === 'POST');
  await dialog.getByRole('button', { name: '创建任务', exact: true }).click();
  const response = await created; expect(response.status()).toBe(201);
  job = (await response.json()).data;
  await expect(dialog).not.toBeVisible();
  await page.screenshot({ path: evidence + '/authenticated-desktop.png' });
  const stored = await page.evaluate(() => [...Object.values(localStorage), ...Object.values(sessionStorage)]);
  expect(stored.some(value => value.includes('access_token') || value.includes('refresh_token'))).toBeFalsy();
  const revoked = page.waitForResponse(response => response.url().includes('/api/v1/access/disable') && response.request().method() === 'POST');
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录你的工作空间' })).toBeVisible();
  await expect(page.locator('.sidebar')).toHaveCount(0);
  expect((await revoked).status()).toBe(200);
  // The console hides immediately; wait for the separate identity logout navigation to finish.
  await page.waitForURL((process.env.TASKFLOW_ACCESS_BASE_URL || 'http://127.0.0.1:5179') + '/');
  await page.waitForLoadState('networkidle');
  await expect(page.getByRole('heading', { name: '登录你的工作空间' })).toBeVisible();
  expect((await request.get('/api/v1/jobs/selectSummary', { headers: { Authorization: administratorToken } })).status()).toBe(401);
  expect(errors).toEqual([]);
});

test('只读账号隐藏修改按钮，直接请求仍被后端拒绝', async ({ page, request }) => {
  const authorization = await login(page, true);
  await expect(page.getByRole('button', { name: '新建任务', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^编辑任务 / })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^删除任务 / })).toHaveCount(0);
  for (const path of ['jobs/insert', 'jobs/update', 'jobs/delete', 'jobs/enable', 'runs/insert', 'job-sync/updateRetry']) {
    const response = await request.post('/api/v1/' + path, { headers: { Authorization: authorization }, data: {} });
    expect(response.status(), path).toBe(403);
  }
  await page.screenshot({ path: evidence + '/viewer-desktop.png' });
});

test('闲置过期后再次请求回到登录页', async ({ page }) => {
  const authorization = await login(page);
  let calls = 0;
  if (settings.sessionRedis) {
    await page.waitForLoadState('networkidle');
    const result = spawnSync(settings.redisCli, ['-h', '127.0.0.1', '-p', String(settings.sessionRedis.port),
      'PEXPIRE', sessionKey(settings, authorization.slice('Bearer '.length)), '100'], {
      env: { ...process.env, REDISCLI_AUTH: settings.sessionRedis.password }, encoding: 'utf8', windowsHide: true,
    });
    expect(result.status).toBe(0); expect(result.stdout.trim()).toBe('1');
    page.on('request', request => { if (request.url().includes('/api/v1/jobs/selectSummary')) calls++; });
    await page.waitForTimeout(300);
  } else if (process.env.TASKFLOW_ACCESS_IDLE_WAIT_MS) {
    page.on('request', request => { if (request.url().includes('/api/v1/jobs/selectSummary')) calls++; });
    await page.waitForTimeout(Number(process.env.TASKFLOW_ACCESS_IDLE_WAIT_MS));
  } else {
    await page.route('**/api/v1/jobs/selectSummary', route => {
      calls++;
      return route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 'SESSION_EXPIRED', message: '登录已失效' }) });
    });
  }
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录你的工作空间' })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('登录已失效');
  expect(calls).toBe(1);
});

test.afterAll(async ({ request, browser }) => {
  if (!job || !administratorToken) return;
  const context = await browser.newContext({ baseURL: process.env.TASKFLOW_ACCESS_BASE_URL || 'http://127.0.0.1:5179' });
  try {
    const authorization = await login(await context.newPage());
    const response = await request.post('/api/v1/jobs/delete', {
      headers: { Authorization: authorization }, data: { id: job.id, configVersion: job.configVersion },
    });
    expect(response.ok()).toBeTruthy();
  } finally { await context.close(); }
});

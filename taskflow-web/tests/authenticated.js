import { test as base, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';

export const test = base.extend({
  page: async ({ page }, use) => {
    if (!process.env.TASKFLOW_ACCESS_SETTINGS) throw Error('Run via tools/test-execution.ps1 with an isolated identity');
    const settings = JSON.parse(readFileSync(process.env.TASKFLOW_ACCESS_SETTINGS, 'utf8').replace(/^\uFEFF/, ''));
    page.on('request', request => {
      const authorization = request.headers().authorization;
      if (request.url().startsWith(settings.baseUrl + '/api/v1/') && authorization?.startsWith('Bearer ')) {
        page.acceptanceAuthorization = authorization;
      }
    });
    await page.goto('/jobs');
    await page.getByRole('button', { name: '登录 TaskFlow', exact: true }).click();
    await page.locator('#username').fill(settings.username);
    await page.locator('#password').fill(settings.password);
    const current = page.waitForRequest(request => request.url().includes('/api/v1/access/selectCurrent'));
    await page.locator('#kc-login').click();
    page.acceptanceAuthorization = (await current).headers().authorization;
    await expect(page.locator('.signed-in-user')).toHaveText(settings.username);
    await use(page);
  },
  request: async ({ playwright, page, baseURL }, use) => {
    const request = await playwright.request.newContext({ baseURL });
    // Reload/SSO can rotate more than once: each explicit test request uses the page's current JWT.
    // There is no retry or automatic replay of failed requests.
    const authenticated = {};
    for (const method of ['get', 'post']) {
      authenticated[method] = (url, options = {}) => request[method](url, {
        ...options, headers: { Authorization: page.acceptanceAuthorization, ...options.headers },
      });
    }
    try { await use(authenticated); } finally { await request.dispose(); }
  },
});
export { expect };

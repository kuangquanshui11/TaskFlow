import { test, expect } from '@playwright/test';
import path from 'node:path';

test('真实节点查询、筛选、错误恢复和离开页面停止轮询', async ({ page, request }) => {
  test.skip(process.env.TASKFLOW_EXECUTION_E2E !== 'true', '需要独立三服务环境');
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await expect.poll(async () => {
    const response = await request.get('/api/v1/workers/selectById?workerId=worker-default');
    return response.ok() ? (await response.json()).data.status : '';
  }).toBe('ONLINE');
  await page.goto('/workers');
  const row = page.locator('tr[data-worker-id="worker-default"]');
  await expect(row).toContainText('在线');
  await expect(row).toContainText('demoSuccess');
  await expect(row).toContainText('当前派发目标');
  await page.getByRole('checkbox', { name: '自动刷新' }).uncheck();
  if (process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR) {
    await page.screenshot({ path: path.join(process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR, 'workers-desktop.png'), fullPage: true });
  }
  await page.getByLabel('搜索节点标识或名称').fill('missing-node-keyword');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('heading', { name: '没有匹配的节点' })).toBeVisible();
  await page.getByRole('button', { name: '清除筛选' }).click();
  await expect(row).toBeVisible();
  await page.getByLabel('筛选节点状态').selectOption('OFFLINE');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('heading', { name: '没有匹配的节点' })).toBeVisible();
  await page.getByRole('button', { name: '清除筛选' }).click();
  await expect(row).toBeVisible();
  const outage = route => route.fulfill({ status: 503, json: { code: 'SCHEDULER_UNAVAILABLE', message: '调度服务暂时不可用' } });
  await page.route('**/api/v1/workers/select?**', outage);
  await page.getByRole('button', { name: '刷新节点', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('节点状态暂时无法查询');
  await expect(row).toContainText('待刷新');
  await expect(row).not.toContainText('可接收新增派发');
  await page.unroute('**/api/v1/workers/select?**', outage);
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await expect(row).toContainText('可接收新增派发');
  await expect(page.getByRole('alert')).not.toBeVisible();
  await page.getByRole('checkbox', { name: '自动刷新' }).check();
  let calls = 0; page.on('request', r => { if (r.url().includes('/api/v1/workers/select?')) calls++; });
  await page.getByRole('link', { name: '时间预览', exact: true }).click();
  const afterLeave = calls;
  await page.waitForTimeout(5300);
  expect(calls).toBe(afterLeave);
  expect(errors).toEqual([]);
});

test('375px 节点卡片显示长标识、处理器和队列而不溢出', async ({ page }) => {
  const now = new Date();
  const node = {
    workerId: 'worker-' + 'a'.repeat(93), displayName: '长名称执行节点用于检验移动端换行', generation: 42,
    handlerKeys: ['demoSuccess', 'handler' + 'b'.repeat(93)], parallelism: 4, maxPending: 32, runningCount: 2, pendingCount: 3,
    dispatchTransport: 'rabbitmq', dispatchVhost: 'taskflow', dispatchExchange: 'taskflow.dispatch.v1',
    dispatchQueue: 'taskflow.' + 'q'.repeat(190), dispatchRoutingKey: 'worker.default',
    status: 'ONLINE', target: true, dispatchEligible: true, registeredAt: now.toISOString(),
    lastHeartbeatAt: now.toISOString(), expiresAt: new Date(now.getTime() + 30000).toISOString(),
  };
  await page.route('**/api/v1/workers/select?**', route => route.fulfill({ json: { code: 'OK', data: {
    records: [node], total: 1, page: 1, pageSize: 10, summary: { total: 1, online: 1, offline: 0 }, observedAt: now.toISOString(),
  } } }));
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/workers');
  await expect(page.getByRole('heading', { name: '工作节点', exact: true })).toBeVisible();
  await expect(page.locator('.worker-name code')).toHaveText(node.workerId);
  await expect(page.locator('.worker-queue')).toHaveText(node.dispatchQueue);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  if (process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR) {
    await page.screenshot({ path: path.join(process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR, 'workers-mobile.png'), fullPage: true });
  }
});

test('首次查询失败不显示空列表，重试后能区分未注册节点', async ({ page }) => {
  let unavailable = true;
  await page.route('**/api/v1/workers/select?**', route => unavailable
    ? route.fulfill({ status: 503, json: { code: 'SCHEDULER_UNAVAILABLE', message: '节点状态查询失败' } })
    : route.fulfill({ json: { code: 'OK', data: { records: [], total: 0, page: 1, pageSize: 10,
      summary: { total: 0, online: 0, offline: 0 }, observedAt: new Date().toISOString() } } }));
  await page.goto('/workers');
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByRole('heading', { name: '还没有注册节点' })).not.toBeVisible();
  await page.getByRole('checkbox', { name: '自动刷新' }).uncheck();
  unavailable = false;
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await expect(page.getByRole('heading', { name: '还没有注册节点' })).toBeVisible();
  await expect(page.getByRole('alert')).not.toBeVisible();
});

import { test, expect } from '../authenticated.js';
import { join } from 'node:path';

test('全局运行列表：真实筛选、历史 ID、已删除任务、失败重试和移动端', async ({ page, request }) => {
  test.setTimeout(90000);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const created = await request.post('/api/v1/jobs/insert', { data: { name: '全局运行查询-' + Date.now(), handlerKey: 'demoSuccess', maxAttempts: 1, timeoutSeconds: 10 } });
  expect(created.status()).toBe(201);
  const job = (await created.json()).data;
  const ids = [];
  try {
    for (let index = 0; index < 2; index++) {
      if (index) await page.waitForTimeout(1100);
      const accepted = await request.post('/api/v1/runs/insert', { data: { jobId: job.id, configVersion: job.configVersion, requestKey: crypto.randomUUID() } });
      expect(accepted.status()).toBe(202);
      ids.push((await accepted.json()).data.id);
    }
    for (const id of ids) await expect.poll(async () => (await (await request.get('/api/v1/runs/selectById?id=' + id)).json()).data.state).toBe('SUCCEEDED');
    const removed = await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: job.configVersion } });
    expect(removed.status()).toBe(200);
    const initial = page.waitForResponse(r => r.url().includes('/api/v1/runs/selectGlobal?') && r.status() === 200);
    await page.goto('/runs');
    const all = (await (await initial).json()).data;
    const records = page.getByRole('region', { name: '全局运行记录' });
    await expect(records).toHaveAttribute('aria-busy', 'false');
    expect(all.total).toBeGreaterThan(2);
    await expect(records.locator('tbody tr')).toHaveCount(all.records.length);
    async function search() {
      const pending = page.waitForResponse(r => r.url().includes('/api/v1/runs/selectGlobal?') && r.status() === 200);
      await page.getByRole('button', { name: '查询运行', exact: true }).click();
      const response = await pending;
      await expect(records).toHaveAttribute('aria-busy', 'false');
      return { url: new URL(response.url()), data: (await response.json()).data };
    }
    await page.getByLabel('任务 ID', { exact: true }).fill(String(job.id));
    await page.getByLabel('运行状态', { exact: true }).selectOption('SUCCEEDED');
    await page.getByLabel('触发来源', { exact: true }).selectOption('MANUAL');
    const filtered = await search();
    expect(filtered.data.total).toBe(2);
    expect(filtered.data.records.every(row => row.jobId === job.id && row.state === 'SUCCEEDED' && row.triggerType === 'MANUAL')).toBeTruthy();
    expect(filtered.url.searchParams.get('from')).toBeTruthy();
    await page.getByLabel('时间范围').selectOption('all');
    await page.getByLabel('Run ID 精确查询').fill(ids[0]);
    const exact = await search();
    expect(exact.url.searchParams.has('from')).toBeFalsy();
    expect(exact.data.total).toBe(1);
    await expect(records.locator('tbody tr')).toHaveAttribute('data-run-id', ids[0]);
    await page.getByLabel('运行状态', { exact: true }).selectOption('FAILED');
    expect((await search()).data.total).toBe(0);
    await expect(records.getByRole('status')).toContainText('没有匹配的运行记录');
    await page.getByLabel('Run ID 精确查询').fill('');
    await page.getByRole('button', { name: '查询运行', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('不限时间查询需要填写完整的 Run ID');
    await page.getByLabel('时间范围').selectOption('custom');
    await page.getByLabel('创建时间起点').fill('2026-11-01T00:00');
    await page.getByLabel('创建时间终点').fill('2026-11-09T00:00');
    await page.getByRole('button', { name: '查询运行', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('范围不超过 7 天');
    await page.getByLabel('创建时间终点').fill('2026-11-02T00:00');
    const custom = await search();
    expect(new Date(custom.url.searchParams.get('to')) - new Date(custom.url.searchParams.get('from'))).toBe(86400000);
    const reset = page.waitForResponse(r => r.url().includes('/api/v1/runs/selectGlobal?') && r.status() === 200);
    await page.getByRole('button', { name: '重置筛选' }).click(); await reset;
    await expect(records).toHaveAttribute('aria-busy', 'false');
    await page.getByLabel('触发来源', { exact: true }).selectOption('CRON');
    const cron = (await search()).data;
    expect(cron.total).toBeGreaterThan(0); expect(cron.records.every(row => row.triggerType === 'CRON')).toBeTruthy();
    const sized = page.waitForResponse(r => r.url().includes('/api/v1/runs/selectGlobal?') && r.status() === 200);
    await page.getByLabel('每页运行数量').selectOption('100');
    expect((await (await sized).json()).data.pageSize).toBe(100);
    await expect(records).toHaveAttribute('aria-busy', 'false');
    const evidence = process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR;
    if (evidence) await page.screenshot({ path: join(evidence, 'global-runs-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    await expect(page.getByLabel('Run ID 精确查询')).toBeVisible();
    if (evidence) await page.screenshot({ path: join(evidence, 'global-runs-mobile.png'), fullPage: true });
    await page.route('**/api/v1/runs/selectGlobal?*', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'SCHEDULER_UNAVAILABLE', message: '调度服务暂时不可用', data: null }) }));
    await page.getByRole('button', { name: '刷新记录', exact: true }).click();
    await expect(records.getByRole('alert')).toContainText('调度服务暂时不可用');
    await expect(records.locator('tbody tr')).toHaveCount(0);
    await page.unroute('**/api/v1/runs/selectGlobal?*');
    await page.getByRole('button', { name: '重试查询' }).click();
    await expect(records).toHaveAttribute('aria-busy', 'false');
    await expect(records.locator('tbody tr').first()).toBeVisible();
    // 页控件专项使用受控分页响应；筛选与业务记录在上面的真实接口中验证。
    const pagingUrls = [];
    await page.route('**/api/v1/runs/selectGlobal?*', route => {
      const url = new URL(route.request().url()); pagingUrls.push(url);
      const pageNumber = Number(url.searchParams.get('page'));
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', data: {
        records: [all.records[pageNumber === 2 ? 1 : 0]], total: 21, page: pageNumber, pageSize: 20,
      } }) });
    });
    await page.getByRole('button', { name: '重置筛选' }).click();
    await expect(records.locator('.page-number')).toHaveText('1 / 2');
    await page.getByRole('button', { name: '下一页运行' }).click();
    await expect(records.locator('.page-number')).toHaveText('2 / 2');
    await expect(records.locator('tbody tr')).toHaveAttribute('data-run-id', all.records[1].id);
    expect(pagingUrls[1].searchParams.get('from')).toBe(pagingUrls[0].searchParams.get('from'));
    expect(pagingUrls[1].searchParams.get('to')).toBe(pagingUrls[0].searchParams.get('to'));
    await page.getByRole('button', { name: '上一页运行' }).click();
    await expect(records.locator('.page-number')).toHaveText('1 / 2');
    await page.unroute('**/api/v1/runs/selectGlobal?*');
    expect(errors).toEqual([]);
  } finally {
    // 清理仅作用于本测试创建的配置；已删除时无需再次写入。
    const response = await request.get('/api/v1/jobs/selectById?id=' + job.id);
    if (response.status() === 200) await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: (await response.json()).data.configVersion } });
  }
});

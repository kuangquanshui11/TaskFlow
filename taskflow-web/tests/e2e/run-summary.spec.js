import { test, expect } from '../authenticated.js';
import { join } from 'node:path';

test('工作台运行概览：真实统计、窗口切换、失败隔离与空数据', async ({ page }) => {
  const response = page.waitForResponse(r => r.url().includes('/api/v1/runs/selectSummary?') && r.status() === 200);
  await page.goto('/');
  const first = (await (await response).json()).data;
  const panel = page.getByRole('region', { name: '运行概览' });
  await expect(panel.getByText('运行总数', { exact: true })).toBeVisible();
  await expect(panel.getByText('成功率', { exact: true })).toBeVisible();
  await expect(panel.locator('.metric-number').first()).toHaveText(new Intl.NumberFormat('zh-CN').format(first.total));
  await expect(panel.getByRole('heading', { name: '失败 Top 5' })).toBeVisible();
  for (const days of [7, 1]) {
    const updated = page.waitForResponse(r => r.url().includes('/api/v1/runs/selectSummary?') && r.status() === 200);
    await panel.getByRole('button', { name: days === 7 ? '7 天' : '24 小时', exact: true }).click();
    const url = new URL((await updated).url());
    expect(new Date(url.searchParams.get('to')) - new Date(url.searchParams.get('from'))).toBe(days * 86400000);
    await expect(panel).toHaveAttribute('aria-busy', 'false');
  }
  if (process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR) await page.screenshot({ path: join(process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR, 'run-summary-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 375, height: 900 });
  await expect(page.locator('.sidebar')).not.toBeInViewport();
  await expect(panel.getByRole('button', { name: '7 天', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  if (process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR) await page.screenshot({ path: join(process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR, 'run-summary-mobile.png'), fullPage: true });
  await page.route('**/api/v1/runs/selectSummary?*', route => route.fulfill({ status: 503, contentType: 'application/json',
    body: JSON.stringify({ code: 'SCHEDULER_UNAVAILABLE', message: '调度服务暂时不可用', data: null }) }));
  await page.getByRole('button', { name: '刷新数据', exact: true }).click();
  await expect(panel.getByRole('alert')).toContainText('调度服务暂时不可用');
  await expect(page.getByRole('region', { name: '任务配置统计' })).toBeVisible();
  await page.unroute('**/api/v1/runs/selectSummary?*');
  await page.route('**/api/v1/runs/selectSummary?*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', data: {
    ...first, total: 0, failed: 0, succeeded: 0, finished: 0, successRate: null,
    stateCounts: { READY: 0, RUNNING: 0, RETRY_WAIT: 0, SUCCEEDED: 0, FAILED: 0 },
    latency: { avgMillis: null, p95Millis: null, sampled: 0, truncated: false }, failures: [],
  } }) }));
  await panel.getByRole('button', { name: '重试运行统计', exact: true }).click();
  await expect(panel.getByRole('status')).toContainText('暂无数据');
  await expect(panel.locator('.metric-number').first()).toHaveText('0');
  await expect(panel).not.toContainText(/NaN|Infinity/);
});

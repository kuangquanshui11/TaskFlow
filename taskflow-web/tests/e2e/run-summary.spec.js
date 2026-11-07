import { test, expect } from '../authenticated.js';
import { join } from 'node:path';

test('失败统计保留时间窗和分组条件，可查看详情并返回', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  const panel = page.getByRole('region', { name: '运行概览' });
  await expect(panel.getByRole('link', { name: '查看失败运行', exact: true })).toBeVisible();
  const summaryResponse = page.waitForResponse(r => r.url().includes('/api/v1/runs/selectSummary?') && r.ok());
  await panel.getByRole('button', { name: '7 天', exact: true }).click();
  const { data: summary } = await (await summaryResponse).json();
  const failure = summary.failures[0];
  expect(failure).toBeDefined();
  const jobId = failure.jobIdText;
  expect(jobId).toBe(String(failure.jobId));
  const totalLink = panel.getByRole('link', { name: '查看失败运行', exact: true });
  const groupLink = panel.getByRole('link', { name: `查看任务 #${jobId} 的 ${failure.resultCode} 失败运行`, exact: true });
  const listResponse = () => page.waitForResponse(r => r.url().includes('/api/v1/runs/selectGlobal?') && r.ok());
  const evidence = process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR;
  await expect(groupLink).toBeVisible();
  if (evidence) await page.screenshot({ path: join(evidence, 'run-failure-drilldown-overview.png'), fullPage: true });

  const totalPending = listResponse();
  await totalLink.focus();
  await totalLink.press('Enter');
  const totalResponse = await totalPending;
  const totalQuery = new URL(totalResponse.url()).searchParams;
  expect(totalQuery.get('state')).toBe('FAILED');
  expect(Date.parse(totalQuery.get('from'))).toBe(Date.parse(summary.from));
  expect(Date.parse(totalQuery.get('to'))).toBe(Date.parse(summary.to));
  expect(totalQuery.has('jobId')).toBeFalsy();
  expect(totalQuery.has('resultCode')).toBeFalsy();
  expect((await totalResponse.json()).data.total).toBe(summary.failed);

  await page.goBack();
  await panel.getByRole('button', { name: '7 天', exact: true }).click();
  await expect(groupLink).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  const groupUrl = new URL(await groupLink.getAttribute('href'), page.url());
  const groupPending = listResponse();
  await groupLink.click();
  const groupResponse = await groupPending;
  const group = (await groupResponse.json()).data;
  const groupQuery = new URL(groupResponse.url()).searchParams;
  expect(groupQuery.get('state')).toBe('FAILED');
  expect(groupQuery.get('jobId')).toBe(jobId);
  expect(groupQuery.get('resultCode')).toBe(failure.resultCode);
  expect(Date.parse(groupQuery.get('from'))).toBe(Date.parse(groupUrl.searchParams.get('from')));
  expect(Date.parse(groupQuery.get('to'))).toBe(Date.parse(groupUrl.searchParams.get('to')));
  expect(Date.parse(groupQuery.get('to')) - Date.parse(groupQuery.get('from'))).toBe(7 * 86400000);
  expect(group.total).toBe(failure.runCount);
  await expect(page.getByLabel('任务 ID', { exact: true })).toHaveValue(jobId);
  await expect(page.getByLabel('运行状态')).toHaveValue('FAILED');
  await expect(page.getByLabel('结果码')).toHaveValue(failure.resultCode);
  const listUrl = page.url();
  const { id } = group.records[0];
  const detailLink = page.getByRole('link', { name: '查看运行详情 ' + id, exact: true });
  await expect(detailLink).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  if (evidence) await page.screenshot({ path: join(evidence, 'run-failure-drilldown-list-mobile.png'), fullPage: true });
  await detailLink.click();
  await expect(page.getByRole('region', { name: '运行概况' })).toContainText(id);
  await page.reload();
  await expect(page.getByRole('region', { name: '运行概况' })).toContainText(id);
  await page.getByRole('link', { name: '返回全局运行列表' }).click();
  await expect(page).toHaveURL(listUrl);
  await expect(detailLink).toBeVisible();
  await page.reload();
  await expect(page).toHaveURL(listUrl);
  await expect(detailLink).toBeVisible();
  expect(errors).toEqual([]);
});

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

import { test, expect } from '@playwright/test';
import path from 'node:path';

test('配置同步显示真实状态，关闭抽屉后停止轮询', async ({ page, request }) => {
  const name = '配置同步-' + Date.now();
  const created = await request.post('/api/v1/jobs/insert', { data: { name, handlerKey: 'demoSuccess' } });
  expect(created.status()).toBe(201);
  const job = (await created.json()).data;
  let polls = 0;
  page.on('request', request => { if (request.url().includes('/job-sync/selectByJobId')) polls++; });
  await page.goto('/jobs');
  await page.getByRole('button', { name: '查看执行记录 ' + name, exact: true }).click();
  const panel = page.getByRole('region', { name: '配置同步' });
  await expect(panel).toContainText('配置 v1');
  await expect(panel).toContainText('实际自动触发状态请查看下方调度计划');
  if (process.env.TASKFLOW_EXECUTION_E2E === 'true') {
    await expect(panel).toContainText('计划副本已同步', { timeout: 10000 });
  } else await expect(panel).toContainText('等待同步');
  await page.setViewportSize({ width: 375, height: 812 });
  await panel.scrollIntoViewIfNeeded();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  if (process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR) {
    await page.screenshot({ path: path.join(process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR, 'sync-mobile.png') });
  }
  await page.getByRole('dialog', { name: '任务详情' }).getByRole('button', { name: '关闭', exact: true }).click();
  const afterClose = polls;
  await page.waitForTimeout(2300);
  expect(polls).toBe(afterClose);
  expect((await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: 1 } })).ok()).toBeTruthy();
});

test('同步查询失败可恢复，重试提交未确认时不显示已同步', async ({ page, request }) => {
  const name = '同步失败界面-' + Date.now();
  const created = await request.post('/api/v1/jobs/insert', { data: { name, handlerKey: 'demoSuccess' } });
  const job = (await created.json()).data;
  let status = 'unavailable';
  await page.route('**/api/v1/job-sync/selectByJobId?*', route => {
    return route.fulfill({ status: status === 'unavailable' ? 503 : 200, json: status === 'unavailable'
      ? { code: 'DATABASE_UNAVAILABLE', message: '同步状态暂时不可查询' }
      : { code: 'OK', data: { jobId: job.id, configVersion: 1, state: status, attempts: 5, lastErrorCode: 'SCHEDULER_UNAVAILABLE' } } });
  });
  await page.route('**/api/v1/job-sync/updateRetry', route => route.abort('failed'));
  await page.goto('/jobs');
  await page.getByRole('button', { name: '查看执行记录 ' + name, exact: true }).click();
  const panel = page.getByRole('region', { name: '配置同步' });
  await expect(panel.getByRole('alert')).toContainText('同步状态暂时不可查询');
  status = 'FAILED';
  await panel.getByRole('button', { name: '刷新同步状态' }).click();
  await expect(panel).toContainText('同步失败');
  await panel.getByRole('button', { name: '重试同步' }).click();
  await expect(panel.getByRole('alert')).toBeVisible();
  await expect(panel).not.toContainText('计划副本已同步');
  status = 'SYNCED';
  await panel.getByRole('button', { name: '刷新同步状态' }).click();
  await expect(panel).toContainText('计划副本已同步');
  await page.keyboard.press('Escape');
  await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: 1 } });
});

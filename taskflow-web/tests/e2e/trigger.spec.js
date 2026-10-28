import { test, expect } from '@playwright/test';
import path from 'node:path';

test('Cron 到期自动创建运行并执行，停用同步后停止新增', async ({ page, request }) => {
  test.skip(process.env.TASKFLOW_EXECUTION_E2E !== 'true', '需要隔离三服务环境');
  const created = await request.post('/api/v1/jobs/insert', { data: {
    name: '自动调度-' + Date.now(), handlerKey: 'demoSuccess', scheduleType: 'CRON', cronExpression: '*/2 * * * * *',
    timeZone: 'UTC', enabled: true, misfirePolicy: 'FIRE_ONCE', maxAttempts: 1,
  } });
  expect(created.status()).toBe(201);
  const job = (await created.json()).data;
  await page.goto('/jobs');
  await page.getByRole('button', { name: '查看执行记录 ' + job.name, exact: true }).click();
  const plan = page.getByRole('region', { name: '调度计划' });
  await expect(plan).toContainText('已开启自动触发', { timeout: 10000 });
  let history;
  await expect.poll(async () => {
    const response = await request.get('/api/v1/runs/select?jobId=' + job.id);
    history = (await response.json()).data;
    return history.records.some(run => run.triggerType === 'CRON' && run.state === 'SUCCEEDED');
  }, { timeout: 15000 }).toBeTruthy();
  const completed = history.records.find(run => run.state === 'SUCCEEDED');
  const detail = (await (await request.get('/api/v1/runs/selectById?id=' + completed.id)).json()).data;
  expect(detail.triggerType).toBe('CRON');
  expect(detail.scheduledAt).toBeTruthy();
  expect(detail.attempts).toHaveLength(1);
  await expect(page.locator('.run-history-list')).toContainText('定时触发', { timeout: 10000 });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.locator('.run-history-heading').scrollIntoViewIfNeeded();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  if (process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR) {
    await page.screenshot({ path: path.join(process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR, 'cron-trigger-mobile.png') });
  }
  expect((await request.post('/api/v1/jobs/disable', { data: { id: job.id, configVersion: 1 } })).ok()).toBeTruthy();
  await expect.poll(async () => {
    const response = await request.get('/api/v1/job-sync/selectPlanByJobId?id=' + job.id);
    return response.ok() ? (await response.json()).data.calculationState : '';
  }).toBe('DISABLED');
  const paused = (await (await request.get('/api/v1/runs/select?jobId=' + job.id)).json()).data.total;
  await page.waitForTimeout(2500);
  expect((await (await request.get('/api/v1/runs/select?jobId=' + job.id)).json()).data.total).toBe(paused);
  expect(new Set(history.records.map(run => run.scheduledAt)).size).toBe(history.records.length);
  await page.keyboard.press('Escape');
  await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: 2 } });
});

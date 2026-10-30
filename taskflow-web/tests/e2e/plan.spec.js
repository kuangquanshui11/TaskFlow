import { test, expect } from '../authenticated.js';
import path from 'node:path';

test('真实计划查询随 Cron、时区和启停更新，关闭详情后停止刷新', async ({ page, request }) => {
  test.skip(process.env.TASKFLOW_EXECUTION_E2E !== 'true', '需要独立 Scheduler 和 Admin');
  const name = '计划计算-' + Date.now();
  const config = { name, handlerKey: 'demoSuccess', scheduleType: 'CRON', cronExpression: '0 0 9 * * *', timeZone: 'Asia/Shanghai', enabled: true };
  const created = await request.post('/api/v1/jobs/insert', { data: config });
  expect(created.status()).toBe(201);
  const job = (await created.json()).data;
  async function waitPlan(version, state) {
    let result;
    await expect.poll(async () => {
      const response = await request.get('/api/v1/job-sync/selectPlanByJobId?id=' + job.id);
      if (!response.ok()) return '';
      result = (await response.json()).data;
      return `${result.configVersion}:${result.calculationState}`;
    }, { timeout: 10000 }).toBe(`${version}:${state}`);
    return result;
  }
  const first = await waitPlan(1, 'READY');
  expect(first.nextFireTime).toContain('T01:00:00Z');
  await page.goto('/jobs');
  let requests = 0;
  page.on('request', request => { if (request.url().includes('/job-sync/selectPlanByJobId')) requests++; });
  await page.getByRole('button', { name: '查看执行记录 ' + name, exact: true }).click();
  const panel = page.getByRole('region', { name: '调度计划' });
  await expect(panel).toContainText('计划时间已计算');
  await expect(panel).toContainText('Asia/Shanghai');
  await expect(panel.locator('time')).toHaveAttribute('datetime', first.nextFireTime);
  await page.setViewportSize({ width: 375, height: 812 });
  await panel.scrollIntoViewIfNeeded();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  if (process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR) {
    await page.screenshot({ path: path.join(process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR, 'plan-mobile.png') });
  }
  await request.post('/api/v1/jobs/update', { data: { id: job.id, configVersion: 1, job: { ...config, timeZone: 'UTC' } } });
  const second = await waitPlan(2, 'READY');
  expect(second.nextFireTime).toContain('T09:00:00Z');
  await panel.getByRole('button', { name: '刷新调度计划' }).click();
  await expect(panel).toContainText('调度配置 v2');
  await expect(panel.locator('time')).toHaveAttribute('datetime', second.nextFireTime);
  await request.post('/api/v1/jobs/update', { data: { id: job.id, configVersion: 2, job: { ...config, timeZone: 'UTC', cronExpression: '0 30 10 * * *' } } });
  expect((await waitPlan(3, 'READY')).nextFireTime).toContain('T10:30:00Z');
  await request.post('/api/v1/jobs/disable', { data: { id: job.id, configVersion: 3 } });
  expect((await waitPlan(4, 'DISABLED')).nextFireTime).toBeNull();
  await panel.getByRole('button', { name: '刷新调度计划' }).click();
  await expect(panel).toContainText('任务已停用，无下一计划时间');
  await expect(panel.locator('time')).toHaveCount(0);
  await request.post('/api/v1/jobs/enable', { data: { id: job.id, configVersion: 4 } });
  expect((await waitPlan(5, 'READY')).nextFireTime).toBeTruthy();
  const history = await request.get('/api/v1/runs/select?jobId=' + job.id);
  expect((await history.json()).data.total).toBe(0);
  await page.keyboard.press('Escape');
  const closedCount = requests;
  await page.waitForTimeout(2200);
  expect(requests).toBe(closedCount);
  await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: 5 } });
  expect((await waitPlan(6, 'DELETED')).nextFireTime).toBeNull();
});

test('计划查询失败支持重试，旧版本和等待计算不会伪装为最新计划', async ({ page, request }) => {
  const name = '计划状态界面-' + Date.now();
  const created = await request.post('/api/v1/jobs/insert', { data: { name, handlerKey: 'demoSuccess' } });
  const job = (await created.json()).data;
  let mode = 'error';
  await page.route('**/api/v1/job-sync/selectPlanByJobId?*', route => route.fulfill({
    status: mode === 'error' ? 503 : 200,
    json: mode === 'error' ? { code: 'SCHEDULER_UNAVAILABLE', message: '调度计划暂时不可查询' }
      : { code: 'OK', data: { jobId: job.id, configVersion: mode === 'old' ? 0 : 1, calculationState: mode === 'pending' ? 'PENDING' : 'MANUAL', nextFireTime: null, timeZone: 'UTC' } },
  }));
  await page.goto('/jobs');
  await page.getByRole('button', { name: '查看执行记录 ' + name, exact: true }).click();
  const panel = page.getByRole('region', { name: '调度计划' });
  await expect(panel.getByRole('alert')).toContainText('暂时不可查询');
  mode = 'old';
  await panel.getByRole('button', { name: '刷新调度计划' }).click();
  await expect(panel).toContainText('当前展示较旧版本的计划');
  mode = 'pending';
  await panel.getByRole('button', { name: '刷新调度计划' }).click();
  await expect(panel).toContainText('等待计算');
  await expect(panel.locator('time')).toHaveCount(0);
  mode = 'manual';
  await panel.getByRole('button', { name: '刷新调度计划' }).click();
  await expect(panel).toContainText('手动任务，无定时计划');
  await expect(panel).toContainText('当前调度节点已暂停自动触发');
  await page.keyboard.press('Escape');
  await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: 1 } });
});

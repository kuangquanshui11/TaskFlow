import { join } from 'node:path';
import { test, expect } from '../authenticated.js';

const jobId = process.env.TASKFLOW_RECONCILIATION_JOB_ID;
const api = '/api/v1/config-reconciliation/';
test.skip(!jobId, '需要通过 test-execution.ps1 -IncludeConfigReconciliation 注入隔离副本故障');

test('对账发现已确认的计划丢失，补发后核对一致并恢复 Cron', async ({ page, request }) => {
  test.setTimeout(65000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const evidence = process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR;
  const historical = await request.get('/api/v1/job-sync/selectByJobId?id=' + jobId);
  expect((await historical.json()).data.state).toBe('SYNCED');

  await page.getByRole('link', { name: '配置对账', exact: true }).click();
  await expect(page.getByRole('heading', { name: '配置对账', exact: true })).toBeVisible();
  await page.getByLabel('每批数量').fill('100');
  const firstScan = page.waitForResponse(response => response.url().endsWith(api + 'insert'));
  await page.getByRole('button', { name: '开始对账', exact: true }).click();
  const firstBatch = (await (await firstScan).json()).data;
  const row = page.locator(`[data-job-id="${jobId}"]`);
  await expect(row).toContainText('副本缺失');
  await expect(row).toContainText('v1 · 已启用');
  const check = firstBatch.checks.find(item => String(item.source.jobId) === jobId);
  expect(check.difference).toBe('MISSING');
  expect(check.replica).toBeNull();

  await page.getByLabel('仅显示差异').check();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({ path: join(evidence, 'reconciliation-missing.png') });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({ path: join(evidence, 'reconciliation-mobile.png'), animations: 'disabled', fullPage: true });

  const repairIds = [];
  let persistedRepair;
  await page.route('**' + api + 'updateRepair', async route => {
    const { requestId, checkId } = route.request().postDataJSON();
    repairIds.push(requestId);
    expect(checkId).toBe(check.id);
    if (repairIds.length === 1) {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      persistedRepair = (await response.json()).data;
      await route.abort('failed');
    } else await route.continue();
  });
  await row.getByRole('button', { name: '补发原事件', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '确认补发原事件' });
  await dialog.getByRole('button', { name: '确认补发', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('沿用同一请求标识');
  await dialog.getByRole('button', { name: '重试本次补发', exact: true }).click();
  await expect(row).toContainText('已排队，补发后请重新对账确认副本');
  expect(repairIds).toEqual([persistedRepair.requestId, persistedRepair.requestId]);
  expect(persistedRepair.eventId).toBe(check.source.eventId);

  await page.getByRole('button', { name: '查询批次', exact: true }).click();
  await row.getByRole('button', { name: '查看补发记录', exact: true }).click();
  await expect(row).toContainText(persistedRepair.requestId);
  await expect(row).toContainText(persistedRepair.actor);

  // Scan gate uses the production default 10-second interval. Wait before issuing a fresh POST.
  const availableAt = Date.parse(firstBatch.finishedAt) + 10250;
  await new Promise(resolve => setTimeout(resolve, Math.max(0, availableAt - Date.now())));
  await expect.poll(async () => {
    const response = await request.get('/api/v1/job-sync/selectPlanByJobId?id=' + jobId);
    return (await response.json()).data?.calculationState;
  }).toBe('READY');
  const recheck = page.waitForResponse(response => response.url().endsWith(api + 'insert'));
  await page.getByRole('button', { name: '重新核对本批', exact: true }).click();
  const newBatch = (await (await recheck).json()).data;
  expect(newBatch.requestId).not.toBe(firstBatch.requestId);
  expect(newBatch.checks.find(item => String(item.source.jobId) === jobId)).toMatchObject({
    difference: 'MATCH', source: { configVersion: 1 }, replica: { configVersion: 1, enabled: true },
  });
  await expect(row).toContainText('一致');
  await page.screenshot({ path: join(evidence, 'reconciliation-restored.png') });

  await expect.poll(async () => {
    const response = await request.get('/api/v1/runs/select?jobId=' + jobId);
    const history = (await response.json()).data?.records ?? [];
    return history.some(run => run.triggerType === 'CRON' && run.state === 'SUCCEEDED'
      && Date.parse(run.scheduledAt) > Date.parse(newBatch.finishedAt));
  }, { timeout: 15000 }).toBe(true);
  expect(errors).toEqual([]);
});

test('下一批使用返回的游标，查询不可用时不开放补发', async ({ page }) => {
  const scans = [];
  const now = new Date().toISOString();
  await page.route('**' + api + 'insert', async route => {
    const body = route.request().postDataJSON();
    scans.push(body);
    const first = scans.length === 1;
    const id = first ? 7 : 12;
    await route.fulfill({ json: { code: 'OK', data: {
      ...body, state: 'COMPLETED', nextAfterJobId: id, hasMore: first, actor: 'test', finishedAt: now,
      checks: [{ id: `check-${id}`, source: { jobId: id, configVersion: 2, deleted: false, enabled: true },
        replica: null, difference: 'UNAVAILABLE', errorCode: 'PLAN_QUERY_UNAVAILABLE' }],
    } } });
  });
  await page.getByRole('link', { name: '配置对账', exact: true }).click();
  await page.getByLabel('每批数量').fill('1');
  await page.getByRole('button', { name: '开始对账', exact: true }).click();
  const first = page.locator('[data-job-id="7"]');
  await expect(first).toContainText('副本查询不可用');
  await expect(first).toContainText('未核实');
  await expect(first.getByRole('button', { name: '补发原事件' })).toHaveCount(0);
  await page.getByRole('button', { name: '对账下一批' }).click();
  await expect(page.locator('[data-job-id="12"]')).toBeVisible();
  await expect(page.getByRole('button', { name: '对账下一批' })).toHaveCount(0);
  expect(scans).toMatchObject([{ afterJobId: 0, batchSize: 1 }, { afterJobId: 7, batchSize: 1 }]);
  expect(scans[0].requestId).not.toBe(scans[1].requestId);
});

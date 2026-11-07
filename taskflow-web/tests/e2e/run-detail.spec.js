import { join } from 'node:path';
import { test, expect } from '../authenticated.js';

test('独立运行详情保留已删除任务的快照并展示真实重试历史', async ({ page, request }) => {
  test.setTimeout(90000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const name = '运行快照-' + Date.now();
  const payload = { note: 'snapshot-' + 'a'.repeat(120) };
  const created = await request.post('/api/v1/jobs/insert', { data: {
    name, payload, handlerKey: 'demoFailOnce', maxAttempts: 2, retryIntervalSeconds: 1, timeoutSeconds: 10,
  } });
  expect(created.status()).toBe(201);
  const job = (await created.json()).data;
  let deleted = false;
  try {
    const accepted = await request.post('/api/v1/runs/insert', { data: { jobId: job.id, configVersion: job.configVersion, requestKey: crypto.randomUUID() } });
    expect(accepted.status()).toBe(202);
    const { id } = (await accepted.json()).data;
    const detailPath = '/api/v1/runs/selectById?id=' + id;
    await expect.poll(async () => (await (await request.get(detailPath)).json()).data?.state, { timeout: 15000 }).toBe('SUCCEEDED');
    const detail = (await (await request.get(detailPath)).json()).data;
    expect(detail.attempts).toHaveLength(2);
    expect(detail.attempts[0].targetWorkerId).toBeTruthy();
    expect(detail.attempts[0].startedAt).toBeTruthy();
    expect(detail.attempts[0].finishedAt).toBeTruthy();
    const removed = await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: job.configVersion } });
    expect(removed.status()).toBe(200);
    deleted = true;

    await page.goto('/runs');
    await page.getByLabel('时间范围').selectOption('all');
    await page.getByLabel('Run ID 精确查询').fill(id);
    await page.getByRole('button', { name: '查询运行', exact: true }).click();
    await page.getByRole('link', { name: '查看运行详情 ' + id }).click();
    await expect(page).toHaveURL(url => url.pathname === '/runs/' + id && url.searchParams.get('runId') === id);
    const overview = page.getByRole('region', { name: '运行概况' });
    const snapshot = page.getByRole('region', { name: '配置快照' });
    const history = page.getByRole('region', { name: 'Attempt 历史' });
    await expect(overview).toContainText(id);
    await expect(overview).toContainText('成功');
    await expect(snapshot).toContainText(name);
    await expect(snapshot).toContainText('版本 v' + job.configVersion);
    await expect(snapshot).toContainText(payload.note);
    const attempts = history.getByRole('listitem');
    await expect(attempts).toHaveCount(2);
    await expect(attempts.first()).toContainText('第 1 次尝试');
    await expect(attempts.first()).toContainText('HANDLER_FAILED');
    await expect(attempts.first()).toContainText(detail.attempts[0].targetWorkerId);
    await expect(attempts.first()).toContainText('开始派发');
    await expect(attempts.first()).toContainText('记录结束');
    await expect(attempts.first()).toContainText(/\d+ (ms|秒|分钟)/);
    await expect(attempts.last()).toContainText('第 2 次尝试');
    await expect(attempts.last()).toContainText('重试执行成功');

    const evidence = process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR;
    if (evidence) await page.screenshot({ path: join(evidence, 'run-detail-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.reload();
    await expect(snapshot).toContainText(name);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    if (evidence) await page.screenshot({ path: join(evidence, 'run-detail-mobile.png'), fullPage: true });

    await page.route('**' + detailPath, route => route.fulfill({ status: 503, json: { code: 'SCHEDULER_UNAVAILABLE', message: '调度服务暂时不可用' } }));
    await page.getByRole('button', { name: '刷新详情' }).click();
    await expect(page.getByRole('alert')).toContainText('调度服务暂时不可用');
    await expect(history).toHaveCount(0);
    await page.unroute('**' + detailPath);
    await page.getByRole('button', { name: '重试读取' }).click();
    await expect(attempts).toHaveCount(2);

    // 旧 Attempt 可缺少派发目标和时间。受控响应验证这些字段的展示。
    const older = { ...detail.attempts[0], targetWorkerId: null, startedAt: null, finishedAt: null };
    await page.route('**' + detailPath, route => route.fulfill({ json: { code: 'OK', data: { ...detail, attempts: [older] } } }));
    await page.getByRole('button', { name: '刷新详情' }).click();
    await expect(attempts).toHaveCount(1);
    await expect(attempts.first()).toContainText('未记录');
    await expect(attempts.first()).toContainText('暂无数据');
    await page.unroute('**' + detailPath);

    await page.goto('/runs/' + crypto.randomUUID());
    await expect(page.getByRole('alert')).toContainText('运行记录不存在');
    await page.goto('/runs/invalid');
    await expect(page.getByRole('alert')).toContainText('运行链接无效');
    await page.getByRole('link', { name: '返回全局运行列表' }).click();
    await expect(page).toHaveURL('/runs');
    expect(errors).toEqual([]);
  } finally {
    if (!deleted) await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: job.configVersion } });
  }
});

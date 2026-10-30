import { test, expect } from '../authenticated.js';
import path from 'node:path';

test.describe('三服务执行闭环', () => {
  test.skip(process.env.TASKFLOW_EXECUTION_E2E !== 'true', '需要 tools/test-execution.ps1 启动三个隔离服务');
  async function prepare(request, page, name) {
    const created = await request.post('/api/v1/jobs/insert', { data: { name, handlerKey: 'demoSuccess', maxAttempts: 1, timeoutSeconds: 10 } });
    expect(created.status()).toBe(201);
    const job = (await created.json()).data;
    await page.goto('/jobs');
    await page.getByRole('button', { name: '查看执行记录 ' + name, exact: true }).click();
    return { job, dialog: page.getByRole('dialog', { name: '任务详情' }) };
  }
  test('手动执行、状态跟踪、尝试历史和移动端详情', async ({ page, request }) => {
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    const { job, dialog } = await prepare(request, page, '执行浏览器-' + Date.now());
    await dialog.getByRole('button', { name: '立即执行', exact: true }).click();
    await expect(dialog.locator('.run-result-title .run-state')).toHaveText('成功', { timeout: 15000 });
    await expect(dialog.locator('.attempt-list')).toContainText('第 1 次尝试');
    await expect(dialog.locator('.run-history-list li')).toHaveCount(1);
    const evidenceDir = process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR;
    if (evidenceDir) await page.screenshot({ path: path.join(evidenceDir, 'execution-desktop.png') });
    await page.setViewportSize({ width: 375, height: 812 });
    await dialog.locator('.run-result').scrollIntoViewIfNeeded();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    expect(await dialog.evaluate(element => element.scrollWidth <= innerWidth)).toBeTruthy();
    if (evidenceDir) await page.screenshot({ path: path.join(evidenceDir, 'execution-mobile.png') });
    expect(errors).toEqual([]);
    await page.keyboard.press('Escape');
    const removed = await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: job.configVersion } });
    expect(removed.status()).toBe(200);
  });
  test('响应丢失后保留原标识，重新打开详情重试不会创建第二次运行', async ({ page, request }) => {
    const name = '执行幂等-' + Date.now();
    const { job, dialog } = await prepare(request, page, name);
    let received;
    const accepted = new Promise(resolve => { received = resolve; });
    await page.route('**/api/v1/runs/insert', async route => {
      const response = await route.fetch(); expect(response.status()).toBe(202);
      received((await response.json()).data.id); await route.abort('failed');
    });
    await dialog.getByRole('button', { name: '立即执行', exact: true }).click();
    const firstId = await accepted;
    await expect(dialog.getByRole('button', { name: '重试本次提交' })).toBeEnabled();
    await page.unroute('**/api/v1/runs/insert');
    await page.reload();
    await page.getByRole('button', { name: '查看执行记录 ' + name, exact: true }).click();
    const reopened = page.getByRole('dialog', { name: '任务详情' });
    await reopened.getByRole('button', { name: '重试本次提交' }).click();
    await expect(reopened.locator('.run-result-title .run-state')).toHaveText('成功', { timeout: 15000 });
    const history = (await (await request.get('/api/v1/runs/select?jobId=' + job.id)).json()).data;
    expect(history.total).toBe(1); expect(history.records[0].id).toBe(firstId);
    await page.keyboard.press('Escape');
    await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: job.configVersion } });
  });
});

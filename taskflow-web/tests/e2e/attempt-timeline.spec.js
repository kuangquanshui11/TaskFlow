import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect } from '../authenticated.js';

const evidence = process.env.TASKFLOW_EXECUTION_EVIDENCE_DIR;
const caseFile = evidence && join(evidence, 'attempt-timeline-cases.json');
const cases = caseFile && existsSync(caseFile) ? JSON.parse(readFileSync(caseFile, 'utf8').replace(/^\uFEFF/, '')) : null;

test.describe('Attempt 时间线进程验收', () => {
  test.skip(!cases, '通过 tools/test-execution.ps1 -IncludeAttemptTimeline 准备隔离运行');

  test('同一 Run 的首次失败和第二次成功各自展示节点和结束阶段', async ({ page }) => {
    const errors = [];
    const requests = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => {
      if (request.url().includes('/runs/selectAttemptEvents?')) requests.push(request.url());
    });
    await page.goto('/runs/' + cases.retryRunId);
    await expect(page.getByRole('region', { name: '运行概况' })).toContainText(cases.retryRunId);
    const attempts = page.locator('.attempt-history > li');
    await expect(attempts).toHaveCount(2);
    expect(requests).toHaveLength(0);
    for (const [index, phase] of ['Handler 执行失败', 'Handler 执行成功'].entries()) {
      const attempt = attempts.nth(index);
      await expect(attempt).toContainText('第 ' + (index + 1) + ' 次尝试');
      const worker = await attempt.locator('dt').filter({ hasText: '目标 Worker' }).locator('..').locator('dd').innerText();
      expect(worker).toMatch(/^worker-/);
      await attempt.getByText('执行时间线', { exact: true }).click();
      await expect(attempt.getByText(phase, { exact: true })).toBeVisible();
      await expect(attempt).toContainText('目标节点 ' + worker);
    }
    expect(new Set(requests.map(url => new URL(url).searchParams.get('attemptId'))).size).toBe(2);
    expect(errors).toEqual([]);
    await page.screenshot({ path: join(evidence, 'attempt-timeline-retry.png'), fullPage: true });
  });

  test('Worker 强制退出后可辨认租约到期、恢复和第二次成功', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const firstTimeline = url => url.pathname.endsWith('/runs/selectAttemptEvents')
      && url.searchParams.get('runId') === cases.crashRunId && url.searchParams.get('attemptId') === cases.firstAttemptId;
    await page.route(firstTimeline, route => route.fulfill({ json: { code: 'OK', data: cases.offline } }));
    await page.goto('/runs/' + cases.crashRunId);
    await expect(page.getByRole('region', { name: '运行概况' })).toContainText(cases.crashRunId);
    const attempts = page.locator('.attempt-history > li');
    await expect(attempts).toHaveCount(2);
    const first = attempts.first();
    const second = attempts.last();
    await first.getByText('执行时间线', { exact: true }).click();
    await expect(first).toContainText('租约到期，Attempt 已结束');
    await expect(first).toContainText('Worker 事件暂不可读');
    await expect(first).toContainText('目标节点 ' + cases.firstWorkerId);
    await page.screenshot({ path: join(evidence, 'attempt-timeline-worker-offline.png'), fullPage: true });
    await page.unroute(firstTimeline);
    await first.getByRole('button', { name: '刷新时间线' }).click();
    await expect(first).toContainText('Worker 重启后记录中断');
    await expect(first).toContainText('迟到结果已被拒绝');
    await expect(first).toContainText('租约到期，Attempt 已结束');
    await second.getByText('执行时间线', { exact: true }).click();
    await expect(second).toContainText('目标节点 ' + cases.secondWorkerId);
    await expect(second).toContainText('Handler 执行成功');
    await expect(second).toContainText('Scheduler 已确认结果');
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.setViewportSize({ width: 375, height: 812 });
    await expect(second.getByText('Handler 执行成功', { exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    expect(errors).toEqual([]);
    await page.screenshot({ path: join(evidence, 'attempt-timeline-worker-recovered-mobile.png'), fullPage: true });
  });
});

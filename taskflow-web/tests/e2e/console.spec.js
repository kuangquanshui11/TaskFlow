import { test, expect } from '@playwright/test';

async function create(page, name) {
  await page.getByRole('button', { name: '新建任务', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '创建任务', exact: true });
  await dialog.getByLabel('任务名称').fill(name);
  await dialog.getByLabel('处理器').selectOption('demoSuccess');
  await dialog.getByRole('button', { name: '创建任务', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  return page.getByRole('row').filter({ has: page.getByRole('button', { name: name }).filter({ has: page.locator('.job-name-copy') }) });
}

test.beforeEach(async ({ request }) => {
  const probe = await request.get('/api/v1/jobs/selectSummary');
  expect(probe.ok()).toBeTruthy();
});

test('真实接口创建、参数校验、详情、修改、启停、过滤和软删除', async ({ page }) => {
  const jsErrors = []; page.on('pageerror', (error) => jsErrors.push(error.message));
  await page.goto('/jobs');
  const name = '浏览器验收-' + Date.now();
  const row = await create(page, name);
  await expect(row).toBeVisible();
  await row.locator('.job-name').click();
  await expect(page.getByRole('dialog', { name: '任务详情' }).getByText('demoSuccess', { exact: true })).toBeVisible();
  await page.getByRole('dialog', { name: '任务详情' }).getByRole('button', { name: '关闭', exact: true }).click();
  await row.getByRole('button', { name: '编辑任务 ' + name, exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '编辑任务', exact: true });
  await expect(dialog.getByLabel('任务名称')).toHaveValue(name);
  await dialog.getByLabel('任务参数').fill('{bad}');
  await dialog.getByRole('button', { name: '保存修改' }).click();
  await expect(dialog.getByRole('alert')).toContainText('JSON 格式不正确');
  await dialog.getByLabel('任务参数').fill('{"source":"e2e"}');
  await dialog.getByLabel('任务说明').fill('保存真实数据库配置');
  await dialog.getByRole('button', { name: '保存修改' }).click();
  await expect(dialog).not.toBeVisible();
  await row.getByRole('button', { name: '启用任务 ' + name, exact: true }).click();
  await expect(row.getByText('已启用', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: '停用任务 ' + name, exact: true }).click();
  await expect(row.getByText('已停用', { exact: true })).toBeVisible();
  await page.getByRole('textbox', { name: '搜索任务名称' }).fill(name + '-不存在');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('没有匹配的任务', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '清除筛选' }).click();
  await row.getByRole('button', { name: '删除任务 ' + name, exact: true }).click();
  await page.getByRole('dialog', { name: '删除这项任务？' }).getByRole('button', { name: '确认删除' }).click();
  await expect(row).not.toBeVisible();
  expect(jsErrors).toEqual([]);
});

test('并发修改显示版本冲突并保留草稿', async ({ page, request }) => {
  await page.goto('/jobs');
  const name = '冲突验收-' + Date.now();
  const row = await create(page, name);
  await row.getByRole('button', { name: '编辑任务 ' + name, exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '编辑任务', exact: true });
  await expect(dialog.getByLabel('任务名称')).toHaveValue(name);
  const list = await (await request.get('/api/v1/jobs/select?keyword=' + encodeURIComponent(name))).json();
  const job = list.data.records[0];
  const changed = await request.post('/api/v1/jobs/enable', { data: { id: job.id, configVersion: job.configVersion } });
  expect(changed.ok()).toBeTruthy();
  await dialog.getByLabel('任务名称').fill(name + '-未保存草稿');
  await dialog.getByRole('button', { name: '保存修改' }).click();
  await expect(dialog.getByRole('alert')).toContainText('当前填写内容已保留');
  await expect(dialog.getByLabel('任务名称')).toHaveValue(name + '-未保存草稿');
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  const latest = await (await request.get('/api/v1/jobs/selectById?id=' + job.id)).json();
  expect((await request.post('/api/v1/jobs/delete', { data: { id: job.id, configVersion: latest.data.configVersion } })).ok()).toBeTruthy();
});

test('Cron 预览、错误恢复和 Nginx 路由刷新', async ({ page, request }) => {
  await page.goto('/schedule');
  await page.getByRole('button', { name: '生成时间预览' }).click();
  await expect(page.locator('.schedule-timeline li')).toHaveCount(5);
  await page.getByLabel('Cron 表达式', { exact: true }).fill('invalid');
  await page.getByRole('button', { name: '生成时间预览' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByRole('button', { name: '每天 09:00', exact: true }).click();
  await page.getByRole('button', { name: '生成时间预览' }).click();
  await expect(page.locator('.schedule-timeline li')).toHaveCount(5);
  await page.reload();
  await expect(page.getByRole('heading', { name: '时间预览', exact: true })).toBeVisible();
  const missingAsset = await request.get('/assets/missing.js');
  expect(missingAsset.status()).toBe(404);
  const root = await request.get('/index.html');
  expect(root.headers()['cache-control']).toContain('no-cache');
});

test('列表网络错误可以重试，不伪装为空列表', async ({ page }) => {
  await page.route('**/api/v1/jobs/select?*', (route) => route.abort('failed'));
  await page.goto('/jobs');
  await expect(page.getByText('任务列表加载失败', { exact: true })).toBeVisible();
  await expect(page.getByText('还没有任务', { exact: true })).not.toBeVisible();
  await page.unroute('**/api/v1/jobs/select?*');
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await expect(page.getByText('还没有任务', { exact: true })).toBeVisible();
});

test('编辑时更改时间规则，忽略之前尚未返回的预览', async ({ page }) => {
  const jsErrors = []; page.on('pageerror', (error) => jsErrors.push(error.message));
  let release;
  let captured;
  const gate = new Promise((resolve) => { release = resolve; });
  const intercepted = new Promise((resolve) => { captured = resolve; });
  await page.route('**/api/v1/jobs/preview', async (route) => {
    const response = await route.fetch(); captured(); await gate; await route.fulfill({ response });
  });
  await page.goto('/jobs');
  await page.getByRole('button', { name: '新建任务', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '创建任务', exact: true });
  await dialog.getByRole('button', { name: '预览', exact: true }).click();
  await intercepted;
  await dialog.getByLabel('时区', { exact: true }).fill('changing-invalid-zone');
  release();
  await expect(dialog.getByRole('button', { name: '预览', exact: true })).toBeEnabled();
  await expect(dialog.locator('.preview-mini')).not.toBeVisible();
  expect(jsErrors).toEqual([]);
  await page.keyboard.press('Escape');
});

test('375px 导航、任务表与抽屉无页面溢出，支持键盘退出', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '打开导航' }).click();
  await page.getByRole('link', { name: /任务管理/ }).click();
  const name = '移动端验收-' + Date.now();
  const row = await create(page, name);
  await expect(row).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await row.getByRole('button', { name: '编辑任务 ' + name, exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '编辑任务', exact: true });
  await expect(dialog.getByLabel('任务名称')).toHaveValue(name);
  expect(await dialog.evaluate((element) => element.scrollWidth <= window.innerWidth)).toBeTruthy();
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await row.getByRole('button', { name: '删除任务 ' + name, exact: true }).click();
  await page.getByRole('button', { name: '确认删除' }).click();
  await expect(row).not.toBeVisible();
});

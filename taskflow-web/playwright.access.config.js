import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/access',
  outputDir: '../.tools/access-browser/results',
  workers: 1,
  retries: 0,
  timeout: 45000,
  reporter: 'list',
  use: {
    baseURL: process.env.TASKFLOW_ACCESS_BASE_URL || 'http://127.0.0.1:5179',
    channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL || 'chrome',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});

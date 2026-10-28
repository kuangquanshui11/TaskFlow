import { defineConfig } from '@playwright/test';

// Only the isolated Nginx -> H2 services started by test scripts are writable.
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  retries: 0,
  maxFailures: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:8091', channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL || 'chrome', headless: true, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
});

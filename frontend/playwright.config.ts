import { defineConfig } from '@playwright/test';
import { existsSync } from 'node:fs';

const installedChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const executablePath =
  process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ??
  (process.platform === 'darwin' && existsSync(installedChrome) ? installedChrome : undefined);

const liveBaseURL = process.env.DEVFLASH_TEST_BASE_URL;
if (liveBaseURL && !['127.0.0.1', 'localhost', '[::1]'].includes(new URL(liveBaseURL).hostname)) {
  throw new Error('The live browser smoke test requires a loopback test service.');
}

export default defineConfig({
  testDir: './tests',
  forbidOnly: Boolean(process.env.CI),
  workers: 2,
  timeout: 30_000,
  reporter: 'list',
  use: {
    viewport: { width: 1536, height: 1024 },
    reducedMotion: 'reduce',
    launchOptions: { executablePath },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'demo', testMatch: 'reader.spec.ts', use: { baseURL: 'http://127.0.0.1:4173' } },
    { name: 'api-ui', testMatch: 'api-ui.spec.ts', use: { baseURL: 'http://127.0.0.1:4174' } },
    ...(liveBaseURL
      ? [
          {
            name: 'isolated-live',
            testMatch: 'live.spec.ts',
            use: { baseURL: liveBaseURL, trace: 'off' as const, screenshot: 'off' as const },
          },
        ]
      : []),
  ],
  webServer: [
    {
      command: 'npm run demo -- --host 127.0.0.1 --port 4173 --strictPort',
      url: 'http://127.0.0.1:4173',
    },
    {
      command: 'npm run dev -- --host 127.0.0.1 --port 4174 --strictPort',
      url: 'http://127.0.0.1:4174',
    },
  ],
});

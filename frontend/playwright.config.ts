import { defineConfig, devices } from '@playwright/test';

/**
 * Приёмочные проверки интерфейса (P3-4).
 *
 * Почему здесь появился Playwright: два дефекта из UX-BACKLOG — исчезающие на
 * 1366 px кнопки и недоступный с клавиатуры список — не поймал ни один
 * jsdom-тест, потому что jsdom не считает layout. Эти проверки гоняют боевую
 * сборку в настоящем Chromium на реальных ширинах.
 *
 * Прогон локально:  npx playwright install chromium && npm run e2e
 * В CI — отдельная джоба; без установленного браузера тесты явно падают, а не
 * молча пропускаются.
 *
 * Демо-режим (`?demo=1`) выбран намеренно: сервер не нужен, а вёрстка и
 * клавиатура — те же, что в настоящем клиенте.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npx vite preview --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects: [
    { name: '1280', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } } },
    { name: '1366', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 768 } } },
    { name: '1440', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
  ],
});

import { mkdir } from 'node:fs/promises';
import path from 'node:path';

let chromium;
try {
  ({ chromium } = await import('playwright'));
} catch {
  // playwright-core is enough when CHROMIUM_PATH points at a local browser
  // (for example a portable Chromium in a restricted CI/sandbox image).
  ({ chromium } = await import('playwright-core'));
}

const baseUrl = (process.env.SCREENSHOT_BASE_URL ?? 'http://127.0.0.1:4173').replace(/\/$/, '');
const outputDir = path.resolve(process.env.SCREENSHOT_DIR ?? 'artifacts/screenshots');

const locales = [
  {
    code: 'ru',
    browserLocale: 'ru-RU',
    welcome: 'С возвращением',
    demo: 'Посмотреть демо без сервера',
    mailAnchor: 'Входящие',
    emoji: 'Эмодзи',
    rocket: 'ракета',
    message: 'Готово к запуску ',
    adminAnchor: 'Пользователи',
  },
  {
    code: 'en',
    browserLocale: 'en-US',
    welcome: 'Welcome back',
    demo: 'View the demo without a server',
    mailAnchor: 'Inbox',
    emoji: 'Emoji',
    rocket: 'rocket',
    message: 'Ready to launch ',
    adminAnchor: 'Users',
  },
];

async function save(page, name) {
  const file = path.join(outputDir, name);
  await page.screenshot({ path: file, fullPage: false, animations: 'disabled' });
  console.log(`saved ${file}`);
}

async function waitForApp(page) {
  await page.waitForLoadState('domcontentloaded');
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await page.waitForTimeout(700);
}

async function captureLocale(browser, cfg) {
  const context = await browser.newContext({
    locale: cfg.browserLocale,
    timezoneId: 'Europe/Moscow',
    viewport: { width: 1440, height: 900 },
    colorScheme: 'light',
  });
  await context.addInitScript((locale) => {
    try {
      window.localStorage.setItem('corvex.locale', locale);
    } catch {
      // Browser locale remains the fallback.
    }
  }, cfg.code);

  const page = await context.newPage();
  page.on('console', (msg) => {
    if (msg.type() === 'error') console.error(`[${cfg.code}] browser console: ${msg.text()}`);
  });
  page.on('pageerror', (error) => console.error(`[${cfg.code}] page error: ${error.message}`));

  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });
  await page.getByText(cfg.welcome, { exact: true }).waitFor({ state: 'visible' });
  await waitForApp(page);
  await save(page, `login-${cfg.code}.png`);

  await page.getByRole('button', { name: cfg.demo }).click();
  await page.waitForURL(/\/mail$/);
  await page.getByText(cfg.mailAnchor, { exact: true }).first().waitFor({ state: 'visible' });
  await waitForApp(page);
  await save(page, `mail-${cfg.code}.png`);

  await page.goto(`${baseUrl}/chat`, { waitUntil: 'domcontentloaded' });
  const composer = page.locator('textarea').first();
  await composer.waitFor({ state: 'visible' });
  await composer.fill(cfg.message);
  await page.getByRole('button', { name: cfg.emoji, exact: true }).click();
  await page.getByRole('button', { name: cfg.rocket, exact: true }).click();
  if (!(await composer.inputValue()).includes('🚀')) {
    throw new Error(`Rocket emoji was not inserted for locale ${cfg.code}`);
  }
  await composer.press('Enter');
  await page.getByText('🚀', { exact: false }).last().waitFor({ state: 'visible' });
  await page.getByRole('button', { name: cfg.emoji, exact: true }).click();
  await waitForApp(page);
  await save(page, `chat-${cfg.code}.png`);

  await page.goto(`${baseUrl}/admin`, { waitUntil: 'domcontentloaded' });
  await page.getByText(cfg.adminAnchor, { exact: true }).first().waitFor({ state: 'visible' });
  await waitForApp(page);
  await save(page, `admin-${cfg.code}.png`);

  await context.close();
  return page;
}

async function captureMobile(browser) {
  const cfg = locales[0];
  const context = await browser.newContext({
    locale: cfg.browserLocale,
    timezoneId: 'Europe/Moscow',
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
    colorScheme: 'light',
  });
  await context.addInitScript(() => {
    try {
      window.localStorage.setItem('corvex.locale', 'ru');
    } catch {
      // Browser locale remains the fallback.
    }
  });
  const page = await context.newPage();
  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: cfg.demo }).click();
  await page.waitForURL(/\/mail$/);
  await waitForApp(page);
  await save(page, 'mail-mobile-ru.png');
  await context.close();
}

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
});
try {
  for (const cfg of locales) {
    await captureLocale(browser, cfg);
  }
  await captureMobile(browser);
} finally {
  await browser.close();
}

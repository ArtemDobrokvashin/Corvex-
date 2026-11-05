// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AlertSettings } from '../../api/adminApi';
import { useLocaleStore } from '../../i18n';

const apiMocks = vi.hoisted(() => ({
  alertSettings: vi.fn(),
  saveAlertSettings: vi.fn(),
  alertRuntimeStatus: vi.fn(),
}));

vi.mock('../../api/adminApi', () => ({
  adminApi: {
    alertSettings: apiMocks.alertSettings,
    saveAlertSettings: apiMocks.saveAlertSettings,
    alertRuntimeStatus: apiMocks.alertRuntimeStatus,
  },
}));

import AlertSettingsPanel from './AlertSettingsPanel';

const categories = {
  ServiceDown: true,
  HighHttp5xxRate: true,
  MailQueueBacklog: true,
  DiskSpaceLow: true,
  JvmMemoryPressure: true,
};
const settings: AlertSettings = {
  revision: 3,
  updatedAt: '2026-11-04T10:00:00Z',
  source: 'DATABASE',
  repeatNormalMinutes: 240,
  repeatCriticalMinutes: 30,
  categories,
  internalEmailEnabled: true,
  internalRecipients: 'admin@company.local',
  internalFrom: 'alerts@company.local',
  externalEmailEnabled: false,
  externalEmailRecipients: '',
  externalSmtpHost: '',
  externalSmtpPort: 587,
  externalSmtpTlsMode: 'starttls',
  externalSmtpUsername: '',
  externalSmtpPassword: { configured: false, source: 'NONE' },
  externalSmtpFrom: '',
  telegramEnabled: true,
  telegramChatId: '-100123',
  telegramBotToken: { configured: true, source: 'ENVIRONMENT' },
  maxEnabled: false,
  maxChatId: '',
  maxUserId: '',
  maxBotToken: { configured: false, source: 'NONE' },
};

function setInput(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

describe('super-admin runtime alert settings', () => {
  let container: HTMLDivElement;
  let root: Root;
  let revision: number;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    revision = 3;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    apiMocks.alertSettings.mockReset().mockResolvedValue(settings);
    apiMocks.saveAlertSettings.mockReset().mockImplementation(async (request) => {
      revision = 4;
      return {
        ...settings,
        ...request,
        revision,
        updatedAt: '2026-11-04T10:10:00Z',
        telegramBotToken: { configured: true, source: 'UI' },
      };
    });
    apiMocks.alertRuntimeStatus.mockReset().mockImplementation(async () => ({
      revision,
      consumers: ['notification-service', 'alert-relay'].map((consumer) => ({
        consumer, status: 'APPLIED', revision, source: 'ADMIN', refresh: 'SUCCESS', updatedAt: null,
      })),
    }));
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('keeps secrets write-only and applies category/repeat changes with a confirmed revision', async () => {
    await act(async () => {
      root.render(<AlertSettingsPanel demoMode={false} />);
      await Promise.resolve();
      await Promise.resolve();
    });

    const token = container.querySelector<HTMLInputElement>('input[aria-label="Telegram bot token"]')!;
    expect(token.value).toBe('');
    expect(token.type).toBe('password');
    expect(container.textContent).not.toContain('env-secret');

    const critical = container.querySelector<HTMLInputElement>('input[name="repeatCriticalMinutes"]')!;
    await act(async () => setInput(critical, '5'));
    const category = container.querySelector<HTMLInputElement>('input[name="ServiceDown"]')!;
    await act(async () => {
      category.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => setInput(token, 'new-ui-secret'));

    const save = container.querySelector<HTMLButtonElement>('button[type="submit"]')!;
    await act(async () => {
      save.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(apiMocks.saveAlertSettings).toHaveBeenCalledTimes(1);
    const request = apiMocks.saveAlertSettings.mock.calls[0][0];
    expect(request.repeatCriticalMinutes).toBe(5);
    expect(request.categories.ServiceDown).toBe(false);
    expect(request.telegramBotToken).toBe('new-ui-secret');
    expect(request.clearTelegramBotToken).toBe(false);
    expect(token.value).toBe('');
    expect(container.textContent).not.toContain('new-ui-secret');
    expect(container.textContent).toContain('revision 4');
    expect(apiMocks.alertRuntimeStatus.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('shows numeric Telegram and MAX ID examples instead of ambiguous usernames', async () => {
    apiMocks.alertSettings.mockResolvedValue({ ...settings, maxEnabled: true });
    await act(async () => {
      root.render(<AlertSettingsPanel demoMode={false} />);
      await Promise.resolve();
      await Promise.resolve();
    });

    const telegram = container.querySelector<HTMLInputElement>('input[name="telegramChatId"]')!;
    const maxChat = container.querySelector<HTMLInputElement>('input[name="maxChatId"]')!;
    const maxUser = container.querySelector<HTMLInputElement>('input[name="maxUserId"]')!;
    expect(telegram.placeholder).toBe('123456789 or -1001234567890');
    expect(telegram.pattern).toBe('-?[0-9]{1,32}');
    expect(maxChat.placeholder).toBe('123456789');
    expect(maxUser.placeholder).toBe('123456789');
    expect(maxChat.pattern).toBe('-?[0-9]{1,32}');
    expect(container.textContent).toContain('not a username or @username');
    expect(container.textContent).toContain('Fill in exactly one of the two fields');
  });
});

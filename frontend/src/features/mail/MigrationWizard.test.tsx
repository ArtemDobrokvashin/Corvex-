// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import type { MigrationJob, MigrationPreview } from '../../api/migrationApi';

const migrationMocks = vi.hoisted(() => ({
  preview: vi.fn(),
  create: vi.fn(),
  list: vi.fn(),
  get: vi.fn(),
  report: vi.fn(),
  pause: vi.fn(),
  resume: vi.fn(),
  cancel: vi.fn(),
}));

const externalMocks = vi.hoisted(() => ({
  create: vi.fn(),
  presets: vi.fn(),
  oauthProviders: vi.fn(),
  oauthAuthorize: vi.fn(),
  oauthConnection: vi.fn(),
}));

vi.mock('../../api/migrationApi', async () => {
  const actual = await vi.importActual<typeof import('../../api/migrationApi')>('../../api/migrationApi');
  return { ...actual, migrationApi: migrationMocks };
});

vi.mock('../../api/externalMailApi', () => ({ externalMailApi: externalMocks }));

import MigrationWizard from './MigrationWizard';

const preview: MigrationPreview = {
  accountId: 'acc-1',
  includeAllMail: false,
  folders: [
    { remoteFolder: 'INBOX', specialUse: 'INBOX', messageCount: 120, estimatedBytes: 10 * 1024 * 1024 },
    { remoteFolder: 'Sent', specialUse: 'SENT', messageCount: 30, estimatedBytes: 2 * 1024 * 1024 },
  ],
  skippedFolders: ['[Gmail]/All Mail'],
  totalMessages: 150,
  totalBytes: 12 * 1024 * 1024,
  quotaBytes: 5 * 1024 ** 3,
  usedBytes: 0,
  fits: true,
  warnings: [],
};

const job: MigrationJob = {
  id: 'job-1',
  accountId: 'acc-1',
  accountEmail: 'old@company.ru',
  status: 'RUNNING',
  mode: 'FULL',
  includeAllMail: false,
  estimatedMessages: 150,
  estimatedBytes: 12 * 1024 * 1024,
  migratedMessages: 0,
  skippedMessages: 0,
  error: null,
  createdAt: '2026-11-01T10:00:00Z',
  startedAt: '2026-11-01T10:00:01Z',
  finishedAt: null,
  folders: [
    {
      id: 'f-1', remoteFolder: 'INBOX', specialUse: 'INBOX', state: 'RUNNING',
      estimatedMessages: 120, estimatedBytes: 10, migratedMessages: 0, skippedMessages: 0, error: null,
    },
  ],
};

function setInput(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

function button(container: HTMLElement, label: string): HTMLButtonElement {
  const found = [...container.querySelectorAll('button')].find((item) => item.textContent?.includes(label));
  if (!found) throw new Error(`Кнопка не найдена: ${label}`);
  return found as HTMLButtonElement;
}

describe('MigrationWizard', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    migrationMocks.preview.mockReset().mockResolvedValue(preview);
    migrationMocks.create.mockReset().mockResolvedValue(job);
    migrationMocks.get.mockReset().mockResolvedValue(job);
    externalMocks.create.mockReset().mockResolvedValue({ id: 'acc-1' });
    externalMocks.oauthProviders.mockReset().mockResolvedValue([]);
    externalMocks.oauthAuthorize.mockReset();
    externalMocks.oauthConnection.mockReset();
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('offers sources and says plainly that Outlook needs OAuth', async () => {
    await act(async () => {
      root.render(<MigrationWizard onClose={vi.fn()} onDismiss={vi.fn()} onNotNeeded={vi.fn()} />);
      await flush();
    });

    expect(container.textContent).toContain('Move your mail');
    expect(container.textContent).toContain('Mail.ru');
    expect(container.textContent).toContain('Yandex 360');
    expect(container.textContent).toContain('Microsoft 365');
    expect(container.textContent).toContain('Microsoft sign-in only');
  });

  it('sends the postpone decisions to the caller instead of hiding them', async () => {
    const onDismiss = vi.fn();
    const onNotNeeded = vi.fn();
    await act(async () => {
      root.render(<MigrationWizard onClose={vi.fn()} onDismiss={onDismiss} onNotNeeded={onNotNeeded} />);
      await flush();
    });

    await act(async () => button(container, 'Later').click());
    await act(async () => button(container, 'I have nothing to move').click());

    expect(onDismiss).toHaveBeenCalledTimes(1);
    expect(onNotNeeded).toHaveBeenCalledTimes(1);
  });

  it('connects the source, previews folders and starts a job', async () => {
    await act(async () => {
      root.render(<MigrationWizard onClose={vi.fn()} onDismiss={vi.fn()} onNotNeeded={vi.fn()} />);
      await flush();
    });

    await act(async () => button(container, 'Mail.ru').click());

    const email = container.querySelector<HTMLInputElement>('input[type="email"]')!;
    const password = container.querySelector<HTMLInputElement>('input[type="password"]')!;
    await act(async () => {
      setInput(email, 'old@company.ru');
      setInput(password, 'app-password');
    });

    const submit = button(container, 'Test and continue');
    expect(submit.disabled).toBe(false);
    await act(async () => {
      submit.click();
      await flush();
    });

    expect(externalMocks.create).toHaveBeenCalledWith(expect.objectContaining({
      provider: 'MAIL_RU',
      emailAddress: 'old@company.ru',
      secret: 'app-password',
      testBeforeSave: true,
      initialSyncMode: 'NEW_ONLY',
    }));
    expect(migrationMocks.preview).toHaveBeenCalledWith({ accountId: 'acc-1', skipFolders: [], includeAllMail: false });
    expect(container.textContent).toContain('INBOX');
    expect(container.textContent).toContain('Sent');

    await act(async () => {
      button(container, 'Start the transfer').click();
      await flush();
    });

    expect(migrationMocks.create).toHaveBeenCalledWith({ accountId: 'acc-1', includeAllMail: false, skipFolders: [] });
    expect(container.textContent).toContain('Transfer in progress');
    expect(container.textContent).toContain('Pause');
  });

  it('shows a readable error when the source rejects the password', async () => {
    externalMocks.create.mockRejectedValue(new Error('AUTH: Неверный пароль приложения'));
    await act(async () => {
      root.render(<MigrationWizard onClose={vi.fn()} onDismiss={vi.fn()} onNotNeeded={vi.fn()} />);
      await flush();
    });
    await act(async () => button(container, 'Mail.ru').click());

    const email = container.querySelector<HTMLInputElement>('input[type="email"]')!;
    const password = container.querySelector<HTMLInputElement>('input[type="password"]')!;
    await act(async () => {
      setInput(email, 'old@company.ru');
      setInput(password, 'wrong');
    });
    await act(async () => {
      button(container, 'Test and continue').click();
      await flush();
    });

    expect(container.textContent).toContain('Неверный пароль приложения');
    expect(container.textContent).not.toContain('Transfer in progress');
  });
});

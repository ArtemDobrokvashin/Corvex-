// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';

const apiMocks = vi.hoisted(() => ({
  summary: vi.fn(),
  bulk: vi.fn(),
  quotaBump: vi.fn(),
}));
vi.mock('../../api/adminMigrationApi', async () => {
  const actual = await vi.importActual<typeof import('../../api/adminMigrationApi')>('../../api/adminMigrationApi');
  return { ...actual, adminMigrationApi: apiMocks };
});

import MigrationTab from './MigrationTab';

const row = {
  ownerId: 'owner-1',
  email: 'ivan@company.test',
  displayName: 'Иван Петров',
  invitationStatus: 'PENDING',
  migrationState: 'PENDING',
  jobStatus: null,
  jobId: null,
  migratedMessages: 0,
  quotaBytes: 5 * 1024 ** 3,
  usedBytes: 4.9 * 1024 ** 3,
  sourceHost: 'imap.old-company.ru',
  sourceLogin: 'ivan@old-company.ru',
};

const summary = {
  rows: [row],
  totals: { employees: 1, invited: 1, notStarted: 1, running: 0, done: 0, failed: 0, migratedMessages: 0 },
  notes: ['Пароли в CSV не принимаются: сотрудник вводит пароль приложения сам.'],
};

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe('MigrationTab', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    apiMocks.summary.mockReset().mockResolvedValue(summary);
    apiMocks.bulk.mockReset().mockResolvedValue({ rows: 2, invited: 1, skipped: 1, truncated: false, issues: [] });
    apiMocks.quotaBump.mockReset().mockResolvedValue({
      ownerId: 'owner-1', quotaBytes: 10 * 1024 ** 3, usedBytes: row.usedBytes, fits: true, remainingBytes: 5 * 1024 ** 3,
    });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('shows who has not started and admits the quota is too small', async () => {
    await act(async () => {
      root.render(<MigrationTab />);
      await flush();
    });

    expect(container.textContent).toContain('Иван Петров');
    expect(container.textContent).toContain('imap.old-company.ru');
    expect(container.textContent).toContain('Not started');
    expect(container.textContent).toContain('The quota is too small for the archive');
    expect(container.textContent).toContain('Employee mail migration');
  });

  it('uploads the CSV and shows the digest', async () => {
    await act(async () => {
      root.render(<MigrationTab />);
      await flush();
    });

    const input = container.querySelector<HTMLInputElement>('input[type="file"]')!;
    const file = new File(['email,source_host\nivan@company.test,imap.old.ru\n'], 'invite.csv', { type: 'text/csv' });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });

    await act(async () => {
      input.dispatchEvent(new Event('change', { bubbles: true }));
      await flush();
    });

    expect(apiMocks.bulk).toHaveBeenCalledWith(file);
    expect(container.textContent).toContain('CSV processed: 1 invited, 1 skipped.');
  });

  it('raises the quota in gigabytes', async () => {
    await act(async () => {
      root.render(<MigrationTab />);
      await flush();
    });

    const button = [...container.querySelectorAll('button')]
      .find((item) => item.textContent?.includes('Quota'))!;
    await act(async () => button.click());

    const add = container.querySelector<HTMLInputElement>('input[inputmode="decimal"]')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(add, '5');
      add.dispatchEvent(new Event('input', { bubbles: true }));
    });

    const submit = [...container.querySelectorAll('button')]
      .find((item) => item.textContent?.trim() === 'Increase')!;
    await act(async () => {
      submit.click();
      await flush();
    });

    expect(apiMocks.quotaBump).toHaveBeenCalledWith('owner-1', { addBytes: 5 * 1024 ** 3 });
    expect(container.textContent).toContain('The quota was increased');
  });
});

// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';

const apiMocks = vi.hoisted(() => ({
  list: vi.fn(),
  presets: vi.fn(),
  searchOwners: vi.fn(),
  ownerFolders: vi.fn(),
}));

vi.mock('../../api/externalMailApi', () => ({
  externalMailApi: {
    list: apiMocks.list,
    presets: apiMocks.presets,
    searchOwners: apiMocks.searchOwners,
    ownerFolders: apiMocks.ownerFolders,
  },
}));

import ExternalMailAccountsModal from './ExternalMailAccountsModal';
import type { ExternalMailOwner } from '../../api/externalMailApi';

const currentOwner: ExternalMailOwner = {
  id: '11111111-1111-4111-8111-111111111111',
  username: 'admin',
  email: 'admin@company.local',
  displayName: 'Администратор',
  department: 'ИТ',
  status: 'ACTIVE',
  externalAccountCount: 0,
};
const employee: ExternalMailOwner = {
  id: '22222222-2222-4222-8222-222222222222',
  username: 'ivanov',
  email: 'ivanov@company.local',
  displayName: 'Иван Иванов',
  department: 'Бухгалтерия',
  status: 'ACTIVE',
  externalAccountCount: 2,
};

function setInput(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('External Mail employee owner search', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    vi.useFakeTimers();
    useLocaleStore.setState({ locale: 'en' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    apiMocks.list.mockReset().mockResolvedValue([]);
    apiMocks.presets.mockReset().mockResolvedValue([]);
    apiMocks.ownerFolders.mockReset().mockResolvedValue([]);
    apiMocks.searchOwners.mockReset().mockImplementation(async (query: string) => (
      query.includes('Иван') ? [employee] : [currentOwner]
    ));
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.useRealTimers();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('shows an explicit result and selects the employee for accounts, folders and the form', async () => {
    await act(async () => {
      root.render(<ExternalMailAccountsModal
        demoMode={false}
        canAdmin
        currentOwner={currentOwner}
        onClose={vi.fn()}
        onAccountsChanged={vi.fn()}
      />);
      await vi.advanceTimersByTimeAsync(0);
      await flush();
    });

    const search = container.querySelector<HTMLInputElement>('input[aria-label="Search Corvex employees"]')!;
    await act(async () => setInput(search, 'Иван'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
      await flush();
    });

    expect(apiMocks.searchOwners).toHaveBeenCalledWith('Иван', 25);
    const result = Array.from(container.querySelectorAll('button')).find((button) => button.textContent?.includes('Иван Иванов'))!;
    expect(result.textContent).toContain('ivanov@company.local');
    expect(result.textContent).toContain('@ivanov');
    expect(result.textContent).toContain('Бухгалтерия');

    await act(async () => {
      result.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await flush();
    });

    expect(result.getAttribute('aria-pressed')).toBe('true');
    expect(apiMocks.list).toHaveBeenCalledWith(employee.id);
    expect(apiMocks.ownerFolders).toHaveBeenCalledWith(employee.id);
    const formOwner = Array.from(container.querySelectorAll<HTMLInputElement>('input')).find((input) => input.disabled && input.value.includes('ivanov@company.local'));
    expect(formOwner?.value).toContain('Иван Иванов');
  });

  it('clears stale results for an empty response and exposes an HTTP search failure', async () => {
    await act(async () => {
      root.render(<ExternalMailAccountsModal
        demoMode={false}
        canAdmin
        currentOwner={currentOwner}
        onClose={vi.fn()}
      />);
      await vi.advanceTimersByTimeAsync(0);
      await flush();
    });
    const search = container.querySelector<HTMLInputElement>('input[aria-label="Search Corvex employees"]')!;

    apiMocks.searchOwners.mockResolvedValueOnce([]);
    await act(async () => setInput(search, 'unknown'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
      await flush();
    });
    expect(container.textContent).toContain('No employees found');
    expect(container.querySelector('#external-owner-results')?.textContent).not.toContain('admin@company.local');

    apiMocks.searchOwners.mockRejectedValueOnce(new Error('HTTP 503 owner search'));
    await act(async () => setInput(search, 'offline'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
      await flush();
    });
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('HTTP 503 owner search');
    expect(container.querySelector('#external-owner-results')?.textContent).not.toContain('admin@company.local');
  });
});

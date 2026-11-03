// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';

const apiMocks = vi.hoisted(() => ({ listUsers: vi.fn(), updateQuota: vi.fn() }));
vi.mock('../../api/adminApi', () => ({ adminApi: apiMocks }));

import QuotasTab from './QuotasTab';

const user = {
  id: 'user-1', username: 'ivan', email: 'ivan@example.test', displayName: 'Ivan Ivanov',
  source: 'LOCAL', status: 'ACTIVE', department: 'Sales', groups: ['USER'],
  quotaBytes: 5368709120, usedBytes: 4294967296, mustChangePassword: false,
  totpEnabled: false, lastLoginAt: null, createdAt: '2026-01-01T00:00:00Z',
};

function setInput(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

describe('QuotasTab', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    apiMocks.listUsers.mockReset().mockResolvedValue({ content: [user], page: 0, size: 25, totalElements: 1, totalPages: 1 });
    apiMocks.updateQuota.mockReset().mockImplementation(async (_id, quotaBytes) => ({ ...user, quotaBytes }));
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root.render(<QuotasTab />);
      await Promise.resolve();
    });
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('loads actual usage and saves decimal GB through the dedicated endpoint', async () => {
    expect(apiMocks.listUsers).toHaveBeenCalledWith({ q: '', page: 0, size: 25 });
    expect(container.textContent).toContain('4.0 GB');
    expect(container.textContent).toContain('80% used');

    const edit = Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'Edit')!;
    await act(async () => edit.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    const input = container.querySelector<HTMLInputElement>('#quota-user-1')!;
    expect(input.value).toBe('5');
    await act(async () => setInput(input, '2.5'));

    const save = Array.from(input.closest('form')!.querySelectorAll('button')).find((button) => button.textContent === 'Save')!;
    await act(async () => {
      save.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });

    expect(apiMocks.updateQuota).toHaveBeenCalledWith('user-1', 2684354560);
    expect(container.textContent).toContain('Quota for Ivan Ivanov saved: 2.5 GB.');
    expect(container.textContent).toContain('160% used');
  });

  it('keeps the editor open and does not call the API for invalid input', async () => {
    const edit = Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'Edit')!;
    await act(async () => edit.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    const input = container.querySelector<HTMLInputElement>('#quota-user-1')!;
    await act(async () => setInput(input, '-1'));
    const save = Array.from(input.closest('form')!.querySelectorAll('button')).find((button) => button.textContent === 'Save')!;
    await act(async () => save.dispatchEvent(new MouseEvent('click', { bubbles: true })));

    expect(apiMocks.updateQuota).not.toHaveBeenCalled();
    expect(container.textContent).toContain('Enter a non-negative number of gigabytes. Use 0 for unlimited.');
    expect(container.querySelector('#quota-user-1')).not.toBeNull();
  });
});

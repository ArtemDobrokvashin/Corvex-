// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AuditQuery } from '../../api/adminApi';
import { useLocaleStore } from '../../i18n';

const apiMocks = vi.hoisted(() => ({ listAudit: vi.fn() }));

vi.mock('../../api/adminApi', () => ({ adminApi: { listAudit: apiMocks.listAudit } }));

import AuditTab from './AuditTab';

const entry = {
  id: 7,
  actorId: '11111111-1111-4111-8111-111111111111',
  actorName: 'Admin',
  action: 'USER_UPDATED',
  entity: 'user',
  entityId: 'user-7',
  details: { changedFields: ['email'] },
  ipAddress: '192.0.2.7',
  createdAt: '2026-11-03T12:00:00Z',
};

function setInput(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

describe('admin audit filters and pagination', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    apiMocks.listAudit.mockReset().mockImplementation(async (query: AuditQuery) => ({
      content: [entry], page: query.page ?? 0, size: 50, totalElements: 75, totalPages: 2,
    }));
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('uses backend filters and keeps them while changing pages', async () => {
    await act(async () => {
      root.render(<AuditTab demoMode={false} />);
      await Promise.resolve();
    });
    expect(apiMocks.listAudit).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 50 }));

    const action = container.querySelector<HTMLInputElement>('input[placeholder="USER_UPDATED"]')!;
    const from = container.querySelectorAll<HTMLInputElement>('input[type="datetime-local"]')[0];
    const to = container.querySelectorAll<HTMLInputElement>('input[type="datetime-local"]')[1];
    await act(async () => {
      setInput(action, 'USER_UPDATED');
      setInput(from, '2026-11-01T10:00');
      setInput(to, '2026-11-04T10:00');
    });
    const apply = Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'Apply filters')!;
    await act(async () => {
      apply.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });

    expect(apiMocks.listAudit).toHaveBeenLastCalledWith(expect.objectContaining({
      action: 'USER_UPDATED',
      from: new Date('2026-11-01T10:00').toISOString(),
      to: new Date('2026-11-04T10:00').toISOString(),
      page: 0,
      size: 50,
    }));

    const next = Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'Next')!;
    await act(async () => {
      next.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });
    expect(apiMocks.listAudit).toHaveBeenLastCalledWith(expect.objectContaining({ action: 'USER_UPDATED', page: 1 }));
    expect(container.textContent).toContain('USER_UPDATED');
  });
});

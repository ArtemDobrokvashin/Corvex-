// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { useSession, type SessionUser } from '../../store/sessionStore';

const apiMocks = vi.hoisted(() => ({ listAudit: vi.fn(), listGroups: vi.fn(), listUsers: vi.fn(), settings: vi.fn() }));

vi.mock('../../api/adminApi', () => ({
  adminApi: {
    listAudit: apiMocks.listAudit,
    listGroups: apiMocks.listGroups,
    listUsers: apiMocks.listUsers,
    settings: apiMocks.settings,
  },
}));

import AdminPage from './AdminPage';

const user = (permissions: string[]): SessionUser => ({
  id: 'user-1', displayName: 'Test Admin', email: 'admin@company.local', department: 'IT',
  groups: [], permissions,
});

function Location() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}{location.search}</output>;
}

describe('admin section routes', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    apiMocks.listAudit.mockReset().mockResolvedValue({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
    apiMocks.listGroups.mockReset().mockResolvedValue([]);
    apiMocks.listUsers.mockReset().mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    apiMocks.settings.mockReset().mockResolvedValue({
      'mail.maxMessageSizeMb': '25', 'mail.defaultQuotaBytes': '5368709120', 'mail.spamThreshold': '5.0',
      'mail.pop3.enabled': 'true', 'security.passwordMinLength': '10', 'security.requireTotp': 'false',
      'chat.maxFileSizeMb': '25',
    });
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useSession.getState().clearSession();
    useLocaleStore.setState({ locale: 'ru' });
  });

  async function renderAt(path: string, permissions: string[]) {
    useSession.getState().setSession(user(permissions), false);
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/admin/:section?" element={<AdminPage />} />
            <Route path="/mail" element={<div>Mail workspace</div>} />
          </Routes>
          <Location />
        </MemoryRouter>,
      );
      await Promise.resolve();
    });
  }

  it('restores a deep-linked tab and updates the route when another tab is selected', async () => {
    await renderAt('/admin/audit', ['ADMIN_USERS_MANAGE', 'AUDIT_VIEW']);

    expect(container.querySelector('[data-testid="location"]')!.textContent).toBe('/admin/audit');
    expect(container.textContent).toContain('Audit log');
    expect(apiMocks.listAudit).toHaveBeenCalledTimes(1);
    expect(apiMocks.listUsers).not.toHaveBeenCalled();

    const usersButton = Array.from(container.querySelectorAll('button')).find((button) => button.textContent?.startsWith('Users'))!;
    await act(async () => {
      usersButton.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });

    expect(container.querySelector('[data-testid="location"]')!.textContent).toBe('/admin/users');
    expect(apiMocks.listUsers).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 20 }));
  });

  it('redirects an unknown or unauthorized section to the first available real tab', async () => {
    await renderAt('/admin/audit', ['ADMIN_QUOTAS_MANAGE']);

    expect(container.querySelector('[data-testid="location"]')!.textContent).toBe('/admin/quotas');
    expect(container.textContent).toContain('Disk quotas');
    expect(apiMocks.listAudit).not.toHaveBeenCalled();
    expect(apiMocks.listGroups).not.toHaveBeenCalled();
  });

  it('exposes the existing settings API through a direct settings route', async () => {
    await renderAt('/admin/settings', ['ADMIN_SERVER_CONFIG']);

    expect(container.querySelector('[data-testid="location"]')!.textContent).toBe('/admin/settings');
    expect(container.textContent).toContain('Server settings registry');
    expect(apiMocks.settings).toHaveBeenCalledTimes(1);
  });

  it('gives a mail-policy-only administrator a visible route to the existing external mail UI', async () => {
    await renderAt('/admin', ['ADMIN_MAIL_POLICY']);

    expect(container.querySelector('[data-testid="location"]')!.textContent).toBe('/admin/external-mail');
    expect(container.textContent).toContain('Employee external mailboxes');
    expect(apiMocks.listUsers).not.toHaveBeenCalled();

    const open = Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'Open management')!;
    await act(async () => {
      open.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });
    expect(container.querySelector('[data-testid="location"]')!.textContent).toBe('/mail?externalAccounts=1');
  });
});

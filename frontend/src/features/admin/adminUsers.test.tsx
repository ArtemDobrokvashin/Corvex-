// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AdminUser } from '../../api/adminApi';
import { useLocaleStore } from '../../i18n';
import { useSession, type SessionUser } from '../../store/sessionStore';
import { quotaGigabytesToBytes } from './quota';

const apiMocks = vi.hoisted(() => ({
  createUser: vi.fn(), listGroups: vi.fn(), listUsers: vi.fn(), patchUser: vi.fn(), resetUserPassword: vi.fn(),
}));

vi.mock('../../api/adminApi', () => ({
  adminApi: {
    createUser: apiMocks.createUser,
    listGroups: apiMocks.listGroups,
    listUsers: apiMocks.listUsers,
    patchUser: apiMocks.patchUser,
    resetUserPassword: apiMocks.resetUserPassword,
  },
}));

import AdminPage from './AdminPage';

const existing: AdminUser = {
  id: '11111111-1111-4111-8111-111111111111', username: 'ivan', email: 'ivan@company.local',
  displayName: 'Ivan Petrov', source: 'LOCAL', status: 'ACTIVE', department: 'Sales', groups: ['USER'],
  quotaBytes: 5 * 1024 ** 3, usedBytes: 1024, mustChangePassword: false, totpEnabled: false,
  lastLoginAt: null, createdAt: '2026-01-01T00:00:00Z',
};

const sessionUser: SessionUser = {
  id: 'admin-1', displayName: 'Admin', email: 'admin@company.local', department: 'IT',
  groups: ['ADMIN'], permissions: ['ADMIN_USERS_MANAGE'],
};

function setInput(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

describe('admin user routes and actions', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    useSession.getState().setSession(sessionUser, false);
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    apiMocks.listGroups.mockReset().mockResolvedValue([]);
    apiMocks.listUsers.mockReset().mockResolvedValue({ content: [existing], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    apiMocks.patchUser.mockReset().mockImplementation(async (id, patch) => ({ ...existing, ...patch, id }));
    apiMocks.resetUserPassword.mockReset().mockResolvedValue(undefined);
    apiMocks.createUser.mockReset().mockImplementation(async (body) => ({
      ...existing, ...body, id: 'new-user', usedBytes: 0, groups: body.groupCodes,
    }));
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useSession.getState().clearSession();
    useLocaleStore.setState({ locale: 'ru' });
  });

  async function renderUsers() {
    await act(async () => {
      root.render(<MemoryRouter initialEntries={['/admin/users']}><Routes><Route path="/admin/:section?" element={<AdminPage />} /></Routes></MemoryRouter>);
      await Promise.resolve();
    });
  }

  it('passes the department filter and exposes profile editing backed by the general PATCH', async () => {
    await renderUsers();
    expect(apiMocks.listUsers).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 20 }));

    const department = container.querySelector<HTMLInputElement>('input[aria-label="Filter by department"]')!;
    await act(async () => setInput(department, 'Sales'));
    const search = Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'Search')!;
    await act(async () => {
      search.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });
    expect(apiMocks.listUsers).toHaveBeenLastCalledWith(expect.objectContaining({ department: 'Sales', page: 0 }));

    const edit = container.querySelector<HTMLButtonElement>('button[aria-label="Edit profile: ivan"]')!;
    await act(async () => edit.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    const dialog = container.querySelector('[role="dialog"]')!;
    await act(async () => {
      setInput(dialog.querySelector<HTMLInputElement>('input[name="displayName"]')!, 'Ivan Sidorov');
      setInput(dialog.querySelector<HTMLInputElement>('input[name="email"]')!, 'ivan.sidorov@company.local');
      setInput(dialog.querySelector<HTMLInputElement>('input[name="department"]')!, 'Support');
    });
    const save = Array.from(dialog.querySelectorAll('button')).find((button) => button.textContent === 'Save')!;
    await act(async () => {
      save.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });

    expect(apiMocks.patchUser).toHaveBeenCalledWith(existing.id, {
      displayName: 'Ivan Sidorov', email: 'ivan.sidorov@company.local', department: 'Support',
    });
    expect(container.textContent).toContain('Ivan Sidorov');
  });

  it('hides password reset from a delegated administrator', async () => {
    await renderUsers();

    expect(container.querySelector('button[aria-label="Reset password: ivan"]')).toBeNull();
  });

  it('lets a superadmin set a temporary password and marks the required change', async () => {
    useSession.getState().setSession({ ...sessionUser, groups: ['SUPER_ADMIN'] }, false);
    await renderUsers();

    const action = container.querySelector<HTMLButtonElement>('button[aria-label="Reset password: ivan"]')!;
    await act(async () => action.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    const dialog = container.querySelector<HTMLElement>('[role="dialog"]')!;
    await act(async () => {
      setInput(dialog.querySelector<HTMLInputElement>('input[name="newPassword"]')!, 'NewPassword2');
      setInput(dialog.querySelector<HTMLInputElement>('input[name="confirmPassword"]')!, 'NewPassword2');
    });
    const submit = Array.from(dialog.querySelectorAll('button'))
      .find((button) => button.textContent === 'Reset password')!;
    await act(async () => {
      submit.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });

    expect(apiMocks.resetUserPassword).toHaveBeenCalledWith(existing.id, 'NewPassword2');
    expect(container.querySelector('[role="dialog"]')).toBeNull();
    expect(container.textContent).toContain('Password change pending');
    expect(container.textContent).toContain('The password for ivan has been reset.');
  });

  it('sends the initial quota through the existing create-user contract', async () => {
    await renderUsers();
    const newUser = Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'New user')!;
    await act(async () => newUser.dispatchEvent(new MouseEvent('click', { bubbles: true })));

    const form = container.querySelector<HTMLFormElement>('#new-username')!.closest('form')!;
    await act(async () => {
      setInput(form.querySelector<HTMLInputElement>('#new-username')!, 'new.user');
      setInput(form.querySelector<HTMLInputElement>('#new-email')!, 'new.user@company.local');
      setInput(form.querySelector<HTMLInputElement>('#new-name')!, 'New User');
      setInput(form.querySelector<HTMLInputElement>('#new-password')!, 'Secret-12345');
      setInput(form.querySelector<HTMLInputElement>('#new-quota')!, '2,5');
    });
    const create = Array.from(form.querySelectorAll('button')).find((button) => button.textContent === 'Create user')!;
    await act(async () => {
      create.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });

    expect(apiMocks.createUser).toHaveBeenCalledWith(expect.objectContaining({
      username: 'new.user', quotaBytes: quotaGigabytesToBytes('2,5'),
    }));
  });
});

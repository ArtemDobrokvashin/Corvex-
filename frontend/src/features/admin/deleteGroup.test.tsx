// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';

const api = vi.hoisted(() => ({ listGroups: vi.fn(), deleteGroup: vi.fn() }));
vi.mock('../../api/adminApi', () => ({ adminApi: api }));
import AdminPage from './AdminPage';

describe('group deletion', () => {
  it('deletes an empty custom group after confirmation, not a system group', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    useSession.getState().setSession({ id: 'admin', displayName: 'Admin', email: 'a@b.test', groups: ['SUPER_ADMIN'], permissions: ['ADMIN_GROUPS_MANAGE'] }, false);
    api.listGroups.mockResolvedValue([
      { code: 'USER', name: 'User', isSystem: true, enabled: true, permissions: [], memberCount: 2 },
      { code: 'RBAC_TEST', name: 'Test group', isSystem: false, enabled: true, permissions: [], memberCount: 0 },
    ]);
    api.deleteGroup.mockResolvedValue(undefined);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const host = document.createElement('div'); document.body.appendChild(host); const root = createRoot(host);
    await act(async () => { root.render(<MemoryRouter initialEntries={['/admin/groups']}><Routes><Route path="/admin/:section?" element={<AdminPage />} /></Routes></MemoryRouter>); await Promise.resolve(); });
    expect([...host.querySelectorAll('button')].some(b => b.textContent === 'Delete group')).toBe(false);
    const custom = [...host.querySelectorAll('button')].find(b => b.textContent?.includes('Test group'))!;
    await act(async () => custom.click());
    const remove = [...host.querySelectorAll('button')].find(b => b.textContent === 'Delete group')!;
    expect(remove.disabled).toBe(false);
    await act(async () => { remove.click(); await Promise.resolve(); });
    expect(confirm).toHaveBeenCalledOnce();
    expect(api.deleteGroup).toHaveBeenCalledWith('RBAC_TEST');
    expect(host.textContent).not.toContain('Test group');
    await act(async () => root.unmount()); host.remove(); confirm.mockRestore(); useSession.getState().clearSession(); useLocaleStore.setState({ locale: 'ru' });
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';

const apiFetchMock = vi.hoisted(() => vi.fn());

vi.mock('./httpClient', () => ({ apiFetch: apiFetchMock }));

import { adminApi } from './adminApi';

describe('adminApi system endpoints', () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    apiFetchMock.mockResolvedValue({});
  });

  it('loads company analytics without a department parameter by default', async () => {
    await adminApi.analyticsActivity();

    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/admin/analytics/activity');
  });

  it('trims and encodes the selected analytics department', async () => {
    await adminApi.analyticsActivity('  Отдел продаж / Центр  ');

    const url = String(apiFetchMock.mock.calls[0][0]);
    expect(url).toBe('/api/v1/admin/analytics/activity?department=%D0%9E%D1%82%D0%B4%D0%B5%D0%BB+%D0%BF%D1%80%D0%BE%D0%B4%D0%B0%D0%B6+%2F+%D0%A6%D0%B5%D0%BD%D1%82%D1%80');
    expect(new URLSearchParams(url.split('?')[1]).get('department')).toBe('Отдел продаж / Центр');
  });

  it('deletes a group through its dedicated API', async () => {
    await adminApi.deleteGroup('RBAC_TEST');
    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/admin/groups/RBAC_TEST', { method: 'DELETE' });
  });

  it('uses the narrow quota endpoint with an integer byte value', async () => {
    await adminApi.updateQuota('user-7', 2684354560);

    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/admin/users/user-7/quota', {
      method: 'PATCH',
      body: JSON.stringify({ quotaBytes: 2684354560 }),
    });
  });

  it('uses the dedicated superadmin password-reset endpoint with a write-only body', async () => {
    await adminApi.resetUserPassword('user-7', 'NewPassword2');

    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/admin/users/user-7/password-reset', {
      method: 'POST',
      body: JSON.stringify({ newPassword: 'NewPassword2' }),
    });
  });

  it('uses the explicit audit-request backup endpoint without claiming a physical backup', async () => {
    await adminApi.requestBackup();

    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/admin/backup/run', { method: 'POST' });
  });

  it('passes department filtering to the existing users endpoint', async () => {
    await adminApi.listUsers({ q: 'ivan', department: 'Sales / West', page: 2, size: 20 });

    const url = String(apiFetchMock.mock.calls[0][0]);
    expect(new URLSearchParams(url.split('?')[1]).get('department')).toBe('Sales / West');
    expect(new URLSearchParams(url.split('?')[1]).get('page')).toBe('2');
  });

  it('loads and patches the server settings registry without inventing runtime options', async () => {
    await adminApi.settings();
    await adminApi.patchSettings({ 'mail.maxMessageSizeMb': '50' });

    expect(apiFetchMock).toHaveBeenNthCalledWith(1, '/api/v1/admin/settings');
    expect(apiFetchMock).toHaveBeenNthCalledWith(2, '/api/v1/admin/settings', {
      method: 'PATCH',
      body: JSON.stringify({ 'mail.maxMessageSizeMb': '50' }),
    });
  });

  it('encodes every audit filter and pagination parameter', async () => {
    await adminApi.listAudit({
      actorId: 'user-7', action: 'USER_UPDATED', from: '2026-11-01T00:00:00.000Z',
      to: '2026-11-04T00:00:00.000Z', page: 3, size: 50,
    });

    const url = String(apiFetchMock.mock.calls[0][0]);
    const params = new URLSearchParams(url.split('?')[1]);
    expect(url.startsWith('/api/v1/admin/audit?')).toBe(true);
    expect(Object.fromEntries(params)).toEqual({
      actorId: 'user-7', action: 'USER_UPDATED', from: '2026-11-01T00:00:00.000Z',
      to: '2026-11-04T00:00:00.000Z', page: '3', size: '50',
    });
  });
});

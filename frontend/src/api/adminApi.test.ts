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

  it('uses the explicit audit-request backup endpoint without claiming a physical backup', async () => {
    await adminApi.requestBackup();

    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/admin/backup/run', { method: 'POST' });
  });
});

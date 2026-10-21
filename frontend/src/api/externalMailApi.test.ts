import { beforeEach, describe, expect, it, vi } from 'vitest';

const apiFetchMock = vi.hoisted(() => vi.fn());

vi.mock('./httpClient', () => ({ apiFetch: apiFetchMock }));

import { externalMailApi } from './externalMailApi';

describe('externalMailApi owner administration', () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    apiFetchMock.mockResolvedValue([]);
  });

  it('scopes the account list to the employee selected by an administrator', async () => {
    await externalMailApi.list('employee-42');

    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/mail/external-accounts?ownerId=employee-42');
  });

  it('searches Corvex employees with a trimmed query and limit', async () => {
    await externalMailApi.searchOwners('  Иван ivanov@company.local  ', 10);

    const url = String(apiFetchMock.mock.calls[0][0]);
    expect(url).toContain('/api/v1/mail/external-accounts/owners?');
    expect(url).toContain('limit=10');
    expect(url).toContain('q=');
    expect(new URLSearchParams(url.split('?')[1]).get('q')).toBe('Иван ivanov@company.local');
  });

  it('loads target folders from the selected owner endpoint', async () => {
    await externalMailApi.ownerFolders('employee-42');

    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/mail/external-accounts/owners/employee-42/folders');
  });
});

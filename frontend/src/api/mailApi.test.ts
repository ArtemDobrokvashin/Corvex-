import { beforeEach, describe, expect, it, vi } from 'vitest';

const apiFetchMock = vi.hoisted(() => vi.fn());
vi.mock('./httpClient', () => ({ apiFetch: apiFetchMock }));

import { mailApi } from './mailApi';

describe('mailApi quota', () => {
  beforeEach(() => apiFetchMock.mockReset().mockResolvedValue({}));

  it('loads the current user quota from the JWT-scoped endpoint', async () => {
    await mailApi.quota();

    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/mail/quota');
  });
});

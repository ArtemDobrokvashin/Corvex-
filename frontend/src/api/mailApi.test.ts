import { beforeEach, describe, expect, it, vi } from 'vitest';

const apiFetchMock = vi.hoisted(() => vi.fn());
const apiBlobMock = vi.hoisted(() => vi.fn());
vi.mock('./httpClient', () => ({ apiFetch: apiFetchMock, apiBlob: apiBlobMock }));

import { mailApi } from './mailApi';

describe('mailApi quota', () => {
  beforeEach(() => apiFetchMock.mockReset().mockResolvedValue({}));

  it('downloads an attachment through the authenticated binary client', async () => {
    await mailApi.downloadAttachment('message-id', 2);
    expect(apiBlobMock).toHaveBeenCalledWith('/api/v1/mail/messages/message-id/attachments/2');
  });

  it('loads the current user quota from the JWT-scoped endpoint', async () => {
    await mailApi.quota();

    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/mail/quota');
  });
});

describe('mail folder API', () => {
  beforeEach(() => { apiFetchMock.mockReset().mockResolvedValue({ id: 'new-folder' }); });

  it('posts the name and optional parent to the public folder endpoint', async () => {
    await mailApi.createFolder('Projects');
    expect(apiFetchMock).toHaveBeenLastCalledWith('/api/v1/mail/folders', {
      method: 'POST', body: JSON.stringify({ name: 'Projects', parentId: null }),
    });
    await mailApi.createFolder('Child', 'parent-id');
    expect(apiFetchMock).toHaveBeenLastCalledWith('/api/v1/mail/folders', {
      method: 'POST', body: JSON.stringify({ name: 'Child', parentId: 'parent-id' }),
    });
  });
});

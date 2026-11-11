import { beforeEach, describe, expect, it, vi } from 'vitest';

const apiFetchMock = vi.hoisted(() => vi.fn());
const apiBlobMock = vi.hoisted(() => vi.fn());
vi.mock('./httpClient', () => ({ apiFetch: apiFetchMock, apiBlob: apiBlobMock }));

import { mailApi } from './mailApi';

describe('mailApi quota', () => {
  beforeEach(() => apiFetchMock.mockReset().mockResolvedValue({}));

  it('creates a personal root folder through the authenticated API', async () => {
    await mailApi.createFolder('Проекты');
    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/mail/folders', { method: 'POST', body: JSON.stringify({ name: 'Проекты', parentId: null }) });
  });

  it('deletes a folder through the authenticated API', async () => {
    await mailApi.deleteFolder('id');
    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/mail/folders/id', { method: 'DELETE' });
  });

  it('downloads an attachment through the authenticated binary client', async () => {
    await mailApi.downloadAttachment('message-id', 2);
    expect(apiBlobMock).toHaveBeenCalledWith('/api/v1/mail/messages/message-id/attachments/2');
  });

  it('loads the current user quota from the JWT-scoped endpoint', async () => {
    await mailApi.quota();

    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/mail/quota');
  });
});

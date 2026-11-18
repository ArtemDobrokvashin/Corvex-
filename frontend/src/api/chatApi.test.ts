import { describe, expect, it, vi } from 'vitest';

const apiBlobMock = vi.hoisted(() => vi.fn());
vi.mock('./httpClient', () => ({ apiFetch: vi.fn(), apiBlob: apiBlobMock }));

import { chatApi } from './chatApi';

describe('chat file preview', () => {
  it('requests a converted PDF for an Office attachment', async () => {
    await chatApi.previewFile('file-ref');
    expect(apiBlobMock).toHaveBeenCalledWith('/api/v1/files/file-ref/preview');
  });

  it('passes the abort signal while previewing', async () => {
    const controller = new AbortController();
    await chatApi.previewFile('file ref', controller.signal);
    expect(apiBlobMock).toHaveBeenCalledWith('/api/v1/files/file%20ref/preview', { signal: controller.signal });
  });
});

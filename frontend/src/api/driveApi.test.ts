import { beforeEach, describe, expect, it, vi } from 'vitest';
import { absoluteShareUrl, formatBytes, driveApi } from './driveApi';

const apiFetchMock = vi.hoisted(() => vi.fn());
const apiBlobMock = vi.hoisted(() => vi.fn());

vi.mock('./httpClient', () => ({
  apiFetch: apiFetchMock,
  apiBlob: apiBlobMock,
}));

beforeEach(() => {
  apiFetchMock.mockReset().mockResolvedValue({});
  apiBlobMock.mockReset().mockResolvedValue(new Blob(['content']));
});

describe('диск: размеры и ссылки', () => {
  it('считает размер в десятичных единицах с локальным разделителем', () => {
    expect(formatBytes(0, 'ru')).toBe('0 Б');
    expect(formatBytes(950, 'ru')).toBe('950 Б');
    expect(formatBytes(2048, 'ru')).toBe('2,0 КБ');
    expect(formatBytes(2048, 'en')).toBe('2.0 KB');
    expect(formatBytes(5_000_000_000, 'ru')).toBe('5,0 ГБ');
  });

  it('не показывает мусор при отрицательных и нечисловых значениях', () => {
    expect(formatBytes(Number.NaN, 'en')).toBe('0 B');
    expect(formatBytes(-10, 'ru')).toBe('0 Б');
  });

  it('строит абсолютный адрес ссылки от origin сайта', () => {
    expect(absoluteShareUrl('/api/v1/drive/shared/token', 'https://mail.example.ru'))
      .toBe('https://mail.example.ru/api/v1/drive/shared/token');
  });
});

describe('диск: контракт /api/v1/drive', () => {
  it('запрашивает список с view, parentId и поиском в query', async () => {
    await driveApi.list(null, 'files', '  ');
    expect(apiFetchMock).toHaveBeenNthCalledWith(1, '/api/v1/drive?view=files');

    await driveApi.list('folder/1', 'trash', '  счёт за месяц ');
    const url = String(apiFetchMock.mock.calls[1][0]);
    expect(url.startsWith('/api/v1/drive?')).toBe(true);
    const params = new URLSearchParams(url.split('?')[1]);
    expect(params.get('view')).toBe('trash');
    expect(params.get('parentId')).toBe('folder/1');
    expect(params.get('q')).toBe('счёт за месяц');
  });

  it('читает квоту отдельным GET', async () => {
    await driveApi.usage();
    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/drive/usage');
  });

  it('создаёт папку через /folders', async () => {
    await driveApi.createFolder('folder 1', 'Отчёты');
    expect(apiFetchMock).toHaveBeenCalledWith('/api/v1/drive/folders', {
      method: 'POST',
      body: JSON.stringify({ parentId: 'folder 1', name: 'Отчёты' }),
    });
  });

  it('загружает файл multipart-полем file и parentId в query', async () => {
    const file = new File(['data'], 'счёт.pdf', { type: 'application/pdf' });
    await driveApi.upload('folder/1', file);

    const [url, init] = apiFetchMock.mock.calls[0] as [string, { method: string; body: FormData }];
    expect(url).toBe('/api/v1/drive/files?parentId=folder%2F1');
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
    const sent = init.body.get('file') as File;
    expect(sent.name).toBe('счёт.pdf');
  });

  it('загружает файл в корень без parentId', async () => {
    await driveApi.upload(null, new File(['data'], 'a.txt'));
    expect(apiFetchMock.mock.calls[0][0]).toBe('/api/v1/drive/files');
  });

  it('кодирует идентификатор узла во всех путях', async () => {
    await driveApi.rename('id/1', 'Новое имя');
    await driveApi.move('id/1', 'parent 2');
    await driveApi.trash('id/1');
    await driveApi.restore('id/1');
    await driveApi.purge('id/1');
    await driveApi.download('id/1');

    expect(apiFetchMock).toHaveBeenNthCalledWith(1, '/api/v1/drive/id%2F1/name', {
      method: 'PATCH',
      body: JSON.stringify({ name: 'Новое имя' }),
    });
    expect(apiFetchMock).toHaveBeenNthCalledWith(2, '/api/v1/drive/id%2F1/move', {
      method: 'POST',
      body: JSON.stringify({ parentId: 'parent 2' }),
    });
    expect(apiFetchMock).toHaveBeenNthCalledWith(3, '/api/v1/drive/id%2F1/trash', { method: 'POST' });
    expect(apiFetchMock).toHaveBeenNthCalledWith(4, '/api/v1/drive/id%2F1/restore', { method: 'POST' });
    expect(apiFetchMock).toHaveBeenNthCalledWith(5, '/api/v1/drive/id%2F1', { method: 'DELETE' });
    expect(apiBlobMock).toHaveBeenCalledWith('/api/v1/drive/id%2F1/content');
  });

  it('передаёт срок ссылки и отзывает её по идентификатору share', async () => {
    await driveApi.shares('file-1');
    await driveApi.createShare('file-1', 7);
    await driveApi.createShare('file-1', null);
    await driveApi.revokeShare('share/1');

    expect(apiFetchMock).toHaveBeenNthCalledWith(1, '/api/v1/drive/file-1/shares');
    expect(apiFetchMock).toHaveBeenNthCalledWith(2, '/api/v1/drive/file-1/shares', {
      method: 'POST',
      body: JSON.stringify({ expiresInDays: 7 }),
    });
    expect(apiFetchMock).toHaveBeenNthCalledWith(3, '/api/v1/drive/file-1/shares', {
      method: 'POST',
      body: JSON.stringify({ expiresInDays: null }),
    });
    expect(apiFetchMock).toHaveBeenNthCalledWith(4, '/api/v1/drive/shares/share%2F1', { method: 'DELETE' });
  });

  it('не подменяет ошибку сервера успехом', async () => {
    apiFetchMock.mockRejectedValueOnce(Object.assign(new Error('Файл больше лимита'), { status: 413 }));

    await expect(driveApi.upload(null, new File(['x'], 'big.zip'))).rejects.toMatchObject({
      status: 413,
      message: 'Файл больше лимита',
    });
    await expect(driveApi.usage()).resolves.toEqual({});
  });
});

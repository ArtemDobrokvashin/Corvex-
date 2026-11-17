import { apiBlob, apiFetch } from './httpClient';

/**
 * Контракт личного диска (gateway: /api/v1/drive/**).
 *
 * Модель ссылок повторяет Google Drive: файл приватный по умолчанию, ссылка
 * выдаётся отдельным действием, отзыв закрывает доступ со следующего запроса.
 * Токен показывается один раз — сервер хранит только его хеш.
 */
export type DriveNodeKind = 'FILE' | 'FOLDER';

export interface DriveNode {
  id: string;
  parentId: string | null;
  kind: DriveNodeKind;
  name: string;
  sizeBytes: number;
  contentType?: string | null;
  createdAt: string;
  updatedAt: string;
  trashedAt?: string | null;
}

export interface DriveUsage {
  usedBytes: number;
  quotaBytes: number;
}

export interface DriveShare {
  id: string;
  nodeId: string;
  createdAt: string;
  expiresAt?: string | null;
  revokedAt?: string | null;
}

export interface DriveShareCreated {
  share: DriveShare;
  token: string;
  path: string;
}

export type DriveView = 'files' | 'trash';

const BASE = '/api/v1/drive';
const nodePath = (id: string) => `${BASE}/${encodeURIComponent(id)}`;

export const driveApi = {
  list(parentId: string | null, view: DriveView, query = ''): Promise<DriveNode[]> {
    const params = new URLSearchParams({ view });
    if (parentId) params.set('parentId', parentId);
    if (query.trim()) params.set('q', query.trim());
    return apiFetch<DriveNode[]>(`${BASE}?${params.toString()}`);
  },

  usage(): Promise<DriveUsage> {
    return apiFetch<DriveUsage>(`${BASE}/usage`);
  },

  createFolder(parentId: string | null, name: string): Promise<DriveNode> {
    return apiFetch<DriveNode>(`${BASE}/folders`, {
      method: 'POST',
      body: JSON.stringify({ parentId, name }),
    });
  },

  upload(parentId: string | null, file: File): Promise<DriveNode> {
    const body = new FormData();
    body.append('file', file);
    const query = parentId ? `?parentId=${encodeURIComponent(parentId)}` : '';
    return apiFetch<DriveNode>(`${BASE}/files${query}`, { method: 'POST', body });
  },

  rename(id: string, name: string): Promise<DriveNode> {
    return apiFetch<DriveNode>(`${nodePath(id)}/name`, {
      method: 'PATCH',
      body: JSON.stringify({ name }),
    });
  },

  move(id: string, parentId: string | null): Promise<DriveNode> {
    return apiFetch<DriveNode>(`${nodePath(id)}/move`, {
      method: 'POST',
      body: JSON.stringify({ parentId }),
    });
  },

  trash(id: string): Promise<void> {
    return apiFetch<void>(`${nodePath(id)}/trash`, { method: 'POST' });
  },

  restore(id: string): Promise<void> {
    return apiFetch<void>(`${nodePath(id)}/restore`, { method: 'POST' });
  },

  purge(id: string): Promise<void> {
    return apiFetch<void>(nodePath(id), { method: 'DELETE' });
  },

  download(id: string, signal?: AbortSignal): Promise<Blob> {
    const path = `${nodePath(id)}/content`;
    return signal ? apiBlob(path, { signal }) : apiBlob(path);
  },

  shares(id: string): Promise<DriveShare[]> {
    return apiFetch<DriveShare[]>(`${nodePath(id)}/shares`);
  },

  createShare(id: string, expiresInDays: number | null): Promise<DriveShareCreated> {
    return apiFetch<DriveShareCreated>(`${nodePath(id)}/shares`, {
      method: 'POST',
      body: JSON.stringify({ expiresInDays }),
    });
  },

  revokeShare(shareId: string): Promise<void> {
    return apiFetch<void>(`${BASE}/shares/${encodeURIComponent(shareId)}`, { method: 'DELETE' });
  },
};

/** Абсолютный адрес ссылки строится на клиенте: сервер не знает внешнего имени сайта. */
export function absoluteShareUrl(path: string, origin?: string): string {
  const base = origin ?? (typeof window === 'undefined' ? '' : window.location.origin);
  return `${base}${path}`;
}

/** Размер в десятичных единицах — так же, как квоты почты в интерфейсе. */
export function formatBytes(bytes: number, locale: 'ru' | 'en' = 'ru'): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return locale === 'en' ? '0 B' : '0 Б';
  const units = locale === 'en'
    ? ['B', 'KB', 'MB', 'GB', 'TB']
    : ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  let value = bytes;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit += 1;
  }
  const digits = value >= 10 || unit === 0 ? 0 : 1;
  return `${value.toFixed(digits).replace('.', locale === 'en' ? '.' : ',')} ${units[unit]}`;
}

/** Скачивание blob'а без публичной ссылки: имя берётся из узла, а не из заголовка ответа. */
export async function saveBlob(blob: Blob, name: string): Promise<void> {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  try {
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
  } finally {
    link.remove();
    // A delayed revoke lets Chrome 109 start the download before the URL disappears.
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }
}

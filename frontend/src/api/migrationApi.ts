import { apiFetch } from './httpClient';

/**
 * Перенос почты из внешнего ящика (gateway: /api/v1/mail/migration/**).
 * Задание живёт на сервере: вкладку можно закрыть, прогресс сохраняется.
 */
export interface MigrationScopeRequest {
  accountId: string;
  includeAllMail?: boolean;
  skipFolders?: string[];
}

export interface MigrationFolderEstimate {
  remoteFolder: string;
  specialUse: string | null;
  messageCount: number;
  estimatedBytes: number;
}

export interface MigrationPreview {
  accountId: string;
  includeAllMail: boolean;
  folders: MigrationFolderEstimate[];
  skippedFolders: string[];
  totalMessages: number;
  totalBytes: number;
  quotaBytes: number;
  usedBytes: number;
  fits: boolean;
  warnings: string[];
}

export type MigrationJobStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'PAUSED'
  | 'DONE'
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELLED';

export interface MigrationFolder {
  id: string;
  remoteFolder: string;
  specialUse: string | null;
  state: 'PENDING' | 'RUNNING' | 'DONE' | 'SKIPPED' | 'FAILED';
  estimatedMessages: number;
  estimatedBytes: number;
  migratedMessages: number;
  skippedMessages: number;
  error: string | null;
}

export interface MigrationJob {
  id: string;
  accountId: string;
  accountEmail: string;
  status: MigrationJobStatus;
  mode: 'FULL' | 'DELTA';
  includeAllMail: boolean;
  estimatedMessages: number;
  estimatedBytes: number;
  migratedMessages: number;
  skippedMessages: number;
  error: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  folders: MigrationFolder[];
}

export interface MigrationReport {
  job: MigrationJob;
  quotaBytes: number;
  usedBytes: number;
  remainingBytes: number;
  notes: string[];
}

const BASE = '/api/v1/mail/migration';

export const migrationApi = {
  preview: (request: MigrationScopeRequest) => apiFetch<MigrationPreview>(`${BASE}/preview`, {
    method: 'POST',
    body: JSON.stringify(request),
  }),
  create: (request: MigrationScopeRequest) => apiFetch<MigrationJob>(BASE, {
    method: 'POST',
    body: JSON.stringify(request),
  }),
  list: () => apiFetch<MigrationJob[]>(BASE),
  get: (id: string) => apiFetch<MigrationJob>(`${BASE}/${encodeURIComponent(id)}`),
  report: (id: string) => apiFetch<MigrationReport>(`${BASE}/${encodeURIComponent(id)}/report`),
  pause: (id: string) => apiFetch<MigrationJob>(`${BASE}/${encodeURIComponent(id)}/pause`, { method: 'POST' }),
  resume: (id: string) => apiFetch<MigrationJob>(`${BASE}/${encodeURIComponent(id)}/resume`, { method: 'POST' }),
  cancel: (id: string) => apiFetch<MigrationJob>(`${BASE}/${encodeURIComponent(id)}/cancel`, { method: 'POST' }),
};

/** Размер для человека: ГиБ/МиБ/КиБ, без лишних знаков после запятой у крупных значений. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 Б';
  const units = ['Б', 'КиБ', 'МиБ', 'ГиБ', 'ТиБ'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const digits = value >= 100 || unit === 0 ? 0 : 1;
  return `${value.toFixed(digits).replace('.', ',')} ${units[unit]}`;
}

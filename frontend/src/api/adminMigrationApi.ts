import { apiFetch } from './httpClient';

/**
 * Админский контур переноса (gateway: /api/v1/mail/admin/migrations).
 * Пароли в CSV не принимаются: приглашение только подсказывает источник.
 */
export interface AdminMigrationRow {
  ownerId: string;
  email: string;
  displayName: string;
  invitationStatus: string | null;
  migrationState: string | null;
  jobStatus: string | null;
  jobId: string | null;
  migratedMessages: number;
  quotaBytes: number;
  usedBytes: number;
  sourceHost: string | null;
  sourceLogin: string | null;
}

export interface AdminMigrationTotals {
  employees: number;
  invited: number;
  notStarted: number;
  running: number;
  done: number;
  failed: number;
  migratedMessages: number;
}

export interface AdminMigrationSummary {
  rows: AdminMigrationRow[];
  totals: AdminMigrationTotals;
  notes: string[];
}

export interface AdminMigrationIssue {
  line: number;
  value: string;
  reason: string;
}

export interface AdminMigrationBulkResult {
  rows: number;
  invited: number;
  skipped: number;
  truncated: boolean;
  issues: AdminMigrationIssue[];
}

export interface AdminMigrationQuotaResult {
  ownerId: string;
  quotaBytes: number;
  usedBytes: number;
  fits: boolean;
  remainingBytes: number;
}

const BASE = '/api/v1/mail/admin/migrations';

export const adminMigrationApi = {
  summary: () => apiFetch<AdminMigrationSummary>(BASE),
  bulk: (file: File) => {
    const body = new FormData();
    body.append('file', file);
    return apiFetch<AdminMigrationBulkResult>(`${BASE}/bulk`, { method: 'POST', body });
  },
  quotaBump: (ownerId: string, body: { quotaBytes?: number; addBytes?: number }) =>
    apiFetch<AdminMigrationQuotaResult>(`${BASE}/${encodeURIComponent(ownerId)}/quota-bump`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
};

/** Шаблон CSV для выгрузки: без паролей, иначе файл нарушает правило «пароль вводит владелец». */
export const MIGRATION_CSV_TEMPLATE = 'email,source_host,source_login\nivan@old-company.ru,imap.old-company.ru,ivan@old-company.ru\n';

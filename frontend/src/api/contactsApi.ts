import { apiBlob, apiFetch } from './httpClient';

/** Контракты адресной книги (mail-storage-service, gateway: /api/v1/mail/contacts). */
export interface Contact {
  id: string;
  displayName: string;
  email: string;
  phone: string | null;
  organization: string | null;
  jobTitle: string | null;
  note: string | null;
  groupName: string | null;
  source: 'MANUAL' | 'VCARD' | 'CSV' | string;
  createdAt: string;
  updatedAt: string;
}

export interface ContactInput {
  displayName: string;
  email: string;
  phone?: string | null;
  organization?: string | null;
  jobTitle?: string | null;
  note?: string | null;
  groupName?: string | null;
}

export interface ContactImportIssue {
  line: number;
  value: string;
  reason: string;
}

export interface ContactImportResult {
  total: number;
  created: number;
  updated: number;
  skipped: number;
  truncated: boolean;
  issues: ContactImportIssue[];
}

export type ContactExportFormat = 'vcf' | 'csv';

const BASE = '/api/v1/mail/contacts';

export const contactsApi = {
  list: (query = '', limit = 500) => {
    const params = new URLSearchParams();
    if (query.trim()) params.set('q', query.trim());
    params.set('limit', String(limit));
    return apiFetch<Contact[]>(`${BASE}?${params.toString()}`);
  },
  create: (input: ContactInput) =>
    apiFetch<Contact>(BASE, { method: 'POST', body: JSON.stringify(input) }),
  update: (id: string, input: ContactInput) =>
    apiFetch<Contact>(`${BASE}/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(input) }),
  remove: (id: string) =>
    apiFetch<void>(`${BASE}/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  importFile: (file: File, format: 'AUTO' | 'VCARD' | 'CSV' = 'AUTO') => {
    const body = new FormData();
    body.append('file', file);
    return apiFetch<ContactImportResult>(`${BASE}/import?format=${format}`, { method: 'POST', body });
  },
  exportFile: (format: ContactExportFormat) => apiBlob(`${BASE}/export?format=${format}`),
};

/** Сохранение файла на диск браузером: без серверной ссылки и без утечки адреса. */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

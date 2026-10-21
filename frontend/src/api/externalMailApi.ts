import { apiFetch } from './httpClient';

/** Контракты External Mail Connector (gateway: /api/v1/mail/external-accounts/**). */
export type ExternalMailProvider = 'MAIL_RU' | 'YANDEX' | 'GMAIL' | 'OUTLOOK' | 'CUSTOM';
export type ExternalMailProtocol = 'IMAP' | 'POP3';
export type ExternalMailSecurity = 'SSL_TLS' | 'STARTTLS' | 'PLAIN';
export type ExternalMailAuthType = 'APP_PASSWORD' | 'PASSWORD' | 'OAUTH2';
export type ExternalMailOwnerKind = 'PERSONAL' | 'SHARED_MAILBOX';
export type ExternalMailInitialMode = 'NEW_ONLY' | 'IMPORT_ALL';
export type ExternalMailSyncScope = 'INBOX_ONLY' | 'ALL_FOLDERS';

export interface ExternalMailPreset {
  provider: ExternalMailProvider;
  imapHost: string;
  imapPort: number;
  pop3Host: string;
  pop3Port: number;
  smtpHost: string;
  smtpPort: number;
  incomingSecurity: ExternalMailSecurity;
  smtpSecurity: ExternalMailSecurity;
  hint: string;
}

export interface ExternalMailAccountRequest {
  existingAccountId?: string | null;
  targetUserId?: string | null;
  ownerKind?: ExternalMailOwnerKind;
  label?: string;
  emailAddress?: string;
  displayName?: string | null;
  provider?: ExternalMailProvider;
  authType?: ExternalMailAuthType;
  incomingProtocol?: ExternalMailProtocol;
  incomingHost?: string;
  incomingPort?: number;
  incomingSecurity?: ExternalMailSecurity;
  incomingUsername?: string;
  secret?: string;
  smtpEnabled?: boolean;
  smtpHost?: string | null;
  smtpPort?: number | null;
  smtpSecurity?: ExternalMailSecurity;
  smtpUsername?: string | null;
  targetFolderId?: string | null;
  enabled?: boolean;
  syncEnabled?: boolean;
  sendEnabled?: boolean;
  initialSyncMode?: ExternalMailInitialMode;
  syncScope?: ExternalMailSyncScope;
  pollIntervalSeconds?: number;
  leaveOnServer?: boolean;
  markSeenOnServer?: boolean;
  deleteAfterFetch?: boolean;
  saveSentOnServer?: boolean;
  sentFolderName?: string | null;
  testBeforeSave?: boolean;
}

export interface ExternalMailAccount {
  id: string;
  ownerId: string;
  createdBy: string | null;
  ownerKind: ExternalMailOwnerKind;
  label: string;
  emailAddress: string;
  displayName: string | null;
  provider: ExternalMailProvider;
  authType: ExternalMailAuthType;
  incomingProtocol: ExternalMailProtocol;
  incomingHost: string;
  incomingPort: number;
  incomingSecurity: ExternalMailSecurity;
  incomingUsername: string;
  secretConfigured: boolean;
  smtpEnabled: boolean;
  smtpHost: string | null;
  smtpPort: number | null;
  smtpSecurity: ExternalMailSecurity;
  smtpUsername: string | null;
  targetFolderId: string | null;
  enabled: boolean;
  syncEnabled: boolean;
  sendEnabled: boolean;
  initialSyncMode: ExternalMailInitialMode;
  syncScope: ExternalMailSyncScope;
  pollIntervalSeconds: number;
  leaveOnServer: boolean;
  markSeenOnServer: boolean;
  deleteAfterFetch: boolean;
  saveSentOnServer: boolean;
  sentFolderName: string | null;
  lastSyncAt: string | null;
  lastSuccessAt: string | null;
  nextSyncAt: string | null;
  lastError: string | null;
  consecutiveFailures: number;
  syncRunning: boolean;
  queuedCount: number;
  deliveredCount: number;
  failedCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ExternalMailTestResult {
  incomingOk: boolean;
  incomingMessage: string;
  folderCount: number;
  inboxMessageCount: number;
  smtpTested: boolean;
  smtpOk: boolean;
  smtpMessage: string | null;
}

export interface ExternalMailSyncRun {
  id: string;
  accountId: string;
  trigger: 'SCHEDULED' | 'MANUAL' | 'MIGRATION';
  status: 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';
  startedAt: string;
  finishedAt: string | null;
  foldersCount: number;
  fetchedCount: number;
  queuedCount: number;
  skippedCount: number;
  failedCount: number;
  error: string | null;
}

const BASE = '/api/v1/mail/external-accounts';

export const externalMailApi = {
  presets: () => apiFetch<ExternalMailPreset[]>(`${BASE}/presets`),
  list: () => apiFetch<ExternalMailAccount[]>(BASE),
  create: (request: ExternalMailAccountRequest) => apiFetch<ExternalMailAccount>(BASE, {
    method: 'POST',
    body: JSON.stringify(request),
  }),
  update: (id: string, request: ExternalMailAccountRequest) => apiFetch<ExternalMailAccount>(`${BASE}/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  }),
  remove: (id: string) => apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }),
  setEnabled: (id: string, body: { enabled?: boolean; syncEnabled?: boolean; sendEnabled?: boolean }) =>
    apiFetch<ExternalMailAccount>(`${BASE}/${id}/enabled`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    }),
  testDraft: (request: ExternalMailAccountRequest) => apiFetch<ExternalMailTestResult>(`${BASE}/test`, {
    method: 'POST',
    body: JSON.stringify(request),
  }),
  testStored: (id: string) => apiFetch<ExternalMailTestResult>(`${BASE}/${id}/test`, { method: 'POST' }),
  sync: (id: string) => apiFetch<ExternalMailSyncRun>(`${BASE}/${id}/sync`, { method: 'POST' }),
  migrate: (id: string) => apiFetch<ExternalMailSyncRun>(`${BASE}/${id}/migrate`, { method: 'POST' }),
  resetSync: (id: string) => apiFetch<void>(`${BASE}/${id}/reset-sync`, { method: 'POST' }),
  runs: (id: string) => apiFetch<ExternalMailSyncRun[]>(`${BASE}/${id}/runs`),
};

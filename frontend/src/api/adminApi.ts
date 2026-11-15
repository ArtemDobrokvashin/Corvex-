import { apiFetch } from './httpClient';

/** Клиент admin-service по контракту docs/api/admin-api.yaml. */

export interface PageResult<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AdminUser {
  id: string;
  username: string;
  email: string;
  displayName: string;
  source: string;
  status: 'ACTIVE' | 'BLOCKED' | 'DISABLED' | string;
  department: string | null;
  groups: string[];
  quotaBytes: number;
  usedBytes: number | null;
  mustChangePassword: boolean;
  totpEnabled: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

export interface AdminGroup {
  code: string;
  name: string;
  isSystem: boolean;
  enabled: boolean;
  permissions: string[];
  memberCount: number;
}

export interface AuditEntry {
  id: number;
  actorId: string | null;
  actorName: string | null;
  action: string;
  entity: string | null;
  entityId: string | null;
  details: Record<string, unknown>;
  ipAddress: string | null;
  createdAt: string;
}

export interface SystemHealth {
  services: { name: string; status: string; version: string | null }[];
  diskFreeBytes: number;
  mailQueueDepth: number;
}

export interface AnalyticsActivity {
  scope: 'COMPANY' | 'OWN_UNIT' | string;
  department: string | null;
  usersTotal: number;
  usersActive: number;
  usersBlocked: number;
  usersDisabled: number;
  loginsLast24h: number | null;
  loginsLast7d: number | null;
  generatedAt: string;
}

export interface BackupRequestResult {
  status: 'REQUESTED' | string;
  note: string;
}

export type AdminSettings = Record<string, string>;

export interface AlertSecretState {
  configured: boolean;
  source: 'UI' | 'ENVIRONMENT' | 'NONE';
}

export interface AlertSettings {
  revision: number;
  updatedAt: string | null;
  source: 'DATABASE' | 'ENVIRONMENT';
  repeatNormalMinutes: number;
  repeatCriticalMinutes: number;
  categories: Record<string, boolean>;
  internalEmailEnabled: boolean;
  internalRecipients: string;
  internalFrom: string;
  externalEmailEnabled: boolean;
  externalEmailRecipients: string;
  externalSmtpHost: string;
  externalSmtpPort: number;
  externalSmtpTlsMode: 'starttls' | 'ssl';
  externalSmtpUsername: string;
  externalSmtpPassword: AlertSecretState;
  externalSmtpFrom: string;
  telegramEnabled: boolean;
  telegramChatId: string;
  telegramBotToken: AlertSecretState;
  maxEnabled: boolean;
  maxChatId: string;
  maxUserId: string;
  maxBotToken: AlertSecretState;
}

export interface AlertSettingsRequest extends Omit<AlertSettings,
  'revision' | 'updatedAt' | 'source' | 'externalSmtpPassword' | 'telegramBotToken' | 'maxBotToken'> {
  externalSmtpPassword: string;
  clearExternalSmtpPassword: boolean;
  telegramBotToken: string;
  clearTelegramBotToken: boolean;
  maxBotToken: string;
  clearMaxBotToken: boolean;
}

export interface AlertConsumerStatus {
  consumer: 'notification-service' | 'alert-relay';
  status: 'APPLIED' | 'PENDING' | 'UNAVAILABLE';
  revision: number | null;
  source: string;
  refresh: string;
  updatedAt: string | null;
}

export interface AlertRuntimeStatus {
  revision: number;
  consumers: AlertConsumerStatus[];
}

export interface TelegramChat {
  id: string;
  type: string;
  title: string;
}

export interface TelegramDiscovery {
  botUsername: string;
  chats: TelegramChat[];
}

export interface AuditQuery extends Record<string, string | number | undefined> {
  actorId?: string;
  action?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

const qs = (params: Record<string, string | number | undefined | null>) => {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') s.set(k, String(v));
  }
  const str = s.toString();
  return str ? `?${str}` : '';
};

export const adminApi = {
  listUsers: (params: { q?: string; status?: string; department?: string; page?: number; size?: number }) =>
    apiFetch<PageResult<AdminUser>>(`/api/v1/admin/users${qs(params)}`),

  createUser: (body: {
    username: string;
    email: string;
    displayName: string;
    tempPassword: string;
    department?: string;
    groupCodes?: string[];
    quotaBytes?: number;
  }) => apiFetch<AdminUser>('/api/v1/admin/users', { method: 'POST', body: JSON.stringify(body) }),

  patchUser: (
    userId: string,
    body: Partial<{ displayName: string; email: string; department: string; quotaBytes: number; status: string }>,
  ) => apiFetch<AdminUser>(`/api/v1/admin/users/${userId}`, { method: 'PATCH', body: JSON.stringify(body) }),

  updateQuota: (userId: string, quotaBytes: number) =>
    apiFetch<AdminUser>(`/api/v1/admin/users/${userId}/quota`, {
      method: 'PATCH',
      body: JSON.stringify({ quotaBytes }),
    }),

  blockUser: (userId: string) => apiFetch<void>(`/api/v1/admin/users/${userId}/block`, { method: 'POST' }),

  resetUserPassword: (userId: string, newPassword: string) =>
    apiFetch<void>(`/api/v1/admin/users/${userId}/password-reset`, {
      method: 'POST',
      body: JSON.stringify({ newPassword }),
    }),

  disableUser: (userId: string) => apiFetch<void>(`/api/v1/admin/users/${userId}`, { method: 'DELETE' }),

  setUserGroups: (userId: string, groupCodes: string[]) =>
    apiFetch<AdminUser>(`/api/v1/admin/users/${userId}/groups`, {
      method: 'PUT',
      body: JSON.stringify({ groupCodes }),
    }),

  listGroups: () => apiFetch<AdminGroup[]>('/api/v1/admin/groups'),

  createGroup: (body: { code: string; name: string; permissions?: string[] }) =>
    apiFetch<AdminGroup>('/api/v1/admin/groups', { method: 'POST', body: JSON.stringify(body) }),

  deleteGroup: (groupCode: string) =>
    apiFetch<void>(`/api/v1/admin/groups/${encodeURIComponent(groupCode)}`, { method: 'DELETE' }),

  setGroupPermissions: (groupCode: string, permissions: string[]) =>
    apiFetch<AdminGroup>(`/api/v1/admin/groups/${groupCode}/permissions`, {
      method: 'PUT',
      body: JSON.stringify({ permissions }),
    }),

  listAudit: (params: AuditQuery = {}) =>
    apiFetch<PageResult<AuditEntry>>(`/api/v1/admin/audit${qs(params)}`),

  settings: () => apiFetch<AdminSettings>('/api/v1/admin/settings'),

  patchSettings: (patch: AdminSettings) =>
    apiFetch<AdminSettings>('/api/v1/admin/settings', { method: 'PATCH', body: JSON.stringify(patch) }),

  alertSettings: () => apiFetch<AlertSettings>('/api/v1/admin/alerts/settings'),

  saveAlertSettings: (body: AlertSettingsRequest) =>
    apiFetch<AlertSettings>('/api/v1/admin/alerts/settings', { method: 'PUT', body: JSON.stringify(body) }),

  alertRuntimeStatus: () => apiFetch<AlertRuntimeStatus>('/api/v1/admin/alerts/runtime'),

  discoverTelegramChats: (botToken: string) =>
    apiFetch<TelegramDiscovery>('/api/v1/admin/alerts/telegram/discover', {
      method: 'POST', body: JSON.stringify({ botToken }),
    }),

  systemHealth: () => apiFetch<SystemHealth>('/api/v1/admin/system/health'),

  analyticsActivity: (department?: string) =>
    apiFetch<AnalyticsActivity>(`/api/v1/admin/analytics/activity${qs({ department: department?.trim() })}`),

  /** Регистрирует запрос в аудите; физический backup выполняет планировщик на хосте. */
  requestBackup: () => apiFetch<BackupRequestResult>('/api/v1/admin/backup/run', { method: 'POST' }),
};

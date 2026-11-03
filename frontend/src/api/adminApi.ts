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
  usedBytes: number;
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

  disableUser: (userId: string) => apiFetch<void>(`/api/v1/admin/users/${userId}`, { method: 'DELETE' }),

  setUserGroups: (userId: string, groupCodes: string[]) =>
    apiFetch<AdminUser>(`/api/v1/admin/users/${userId}/groups`, {
      method: 'PUT',
      body: JSON.stringify({ groupCodes }),
    }),

  listGroups: () => apiFetch<AdminGroup[]>('/api/v1/admin/groups'),

  createGroup: (body: { code: string; name: string; permissions?: string[] }) =>
    apiFetch<AdminGroup>('/api/v1/admin/groups', { method: 'POST', body: JSON.stringify(body) }),

  setGroupPermissions: (groupCode: string, permissions: string[]) =>
    apiFetch<AdminGroup>(`/api/v1/admin/groups/${groupCode}/permissions`, {
      method: 'PUT',
      body: JSON.stringify({ permissions }),
    }),

  listAudit: (page = 0, size = 50) =>
    apiFetch<PageResult<AuditEntry>>(`/api/v1/admin/audit${qs({ page, size })}`),

  systemHealth: () => apiFetch<SystemHealth>('/api/v1/admin/system/health'),

  analyticsActivity: (department?: string) =>
    apiFetch<AnalyticsActivity>(`/api/v1/admin/analytics/activity${qs({ department: department?.trim() })}`),

  /** Регистрирует запрос в аудите; физический backup выполняет планировщик на хосте. */
  requestBackup: () => apiFetch<BackupRequestResult>('/api/v1/admin/backup/run', { method: 'POST' }),
};

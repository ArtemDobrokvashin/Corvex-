export type PermissionCheck = (permission: string) => boolean;
type AccessCheck = (has: PermissionCheck) => boolean;

export const canManageUsers: AccessCheck = (has) => has('ADMIN_USERS_MANAGE');
export const canManageGroups: AccessCheck = (has) => has('ADMIN_GROUPS_MANAGE');
export const canViewAudit: AccessCheck = (has) => has('AUDIT_VIEW');
export const canViewSystemHealth: AccessCheck = (has) =>
  has('ADMIN_SERVER_CONFIG') || has('ADMIN_USERS_MANAGE') || has('ADMIN_GROUPS_MANAGE');
export const canManageQuotas: AccessCheck = (has) =>
  has('ADMIN_USERS_MANAGE') || has('ADMIN_QUOTAS_MANAGE');
export const canManageExternalMail: AccessCheck = (has) => has('ADMIN_MAIL_POLICY');
export const canViewSettings: AccessCheck = (has) => has('ADMIN_SERVER_CONFIG');

export const canViewAnalytics: AccessCheck = (has) =>
  has('ANALYTICS_VIEW_COMPANY') || has('ANALYTICS_VIEW_OWN_UNIT');
export const canViewServer: AccessCheck = (has) => canViewSystemHealth(has) || has('ADMIN_BACKUP');

/** Не открываем пустую админку для прав, у которых пока нет самостоятельного runtime UI. */
export const canAccessAdmin: AccessCheck = (has) =>
  canManageUsers(has) || canManageGroups(has) || canManageQuotas(has) ||
  canManageExternalMail(has) || canViewServer(has) || canViewAnalytics(has) || canViewAudit(has);

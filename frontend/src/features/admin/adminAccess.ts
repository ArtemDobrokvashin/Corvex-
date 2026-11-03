export type PermissionCheck = (permission: string) => boolean;

/** Health endpoint принимает эти же три права; ADMIN_BACKUP его не открывает. */
export const canViewSystemHealth = (has: PermissionCheck) => (
  has('ADMIN_SERVER_CONFIG') || has('ADMIN_USERS_MANAGE') || has('ADMIN_GROUPS_MANAGE')
);

export const canViewServer = (has: PermissionCheck) => canViewSystemHealth(has) || has('ADMIN_BACKUP');

export const canViewAnalytics = (has: PermissionCheck) => (
  has('ANALYTICS_VIEW_COMPANY') || has('ANALYTICS_VIEW_OWN_UNIT')
);

/** Доступ к маршруту определяется наличием хотя бы одного реально доступного раздела. */
export const canAccessAdmin = (has: PermissionCheck) => (
  has('ADMIN_USERS_MANAGE')
  || has('ADMIN_GROUPS_MANAGE')
  || canViewServer(has)
  || canViewAnalytics(has)
  || has('AUDIT_VIEW')
);

import { describe, expect, it } from 'vitest';
import { canAccessAdmin, canManageQuotas, canViewAnalytics, canViewServer, canViewSystemHealth } from './adminAccess';

const has = (...permissions: string[]) => (permission: string) => permissions.includes(permission);

describe('admin section access', () => {
  it.each([
    'ADMIN_USERS_MANAGE',
    'ADMIN_GROUPS_MANAGE',
    'ADMIN_QUOTAS_MANAGE',
    'ADMIN_SERVER_CONFIG',
    'ADMIN_BACKUP',
    'ANALYTICS_VIEW_COMPANY',
    'ANALYTICS_VIEW_OWN_UNIT',
    'AUDIT_VIEW',
  ])('opens administration for a role with %s', (permission) => {
    expect(canAccessAdmin(has(permission))).toBe(true);
  });

  it('does not open an empty administration page for unused policy-only permissions', () => {
    expect(canAccessAdmin(has('ADMIN_SECURITY_POLICY', 'ADMIN_MAIL_POLICY'))).toBe(false);
    expect(canAccessAdmin(has())).toBe(false);
  });

  it('does not let a backup-only role load the health endpoint', () => {
    const check = has('ADMIN_BACKUP');
    expect(canViewServer(check)).toBe(true);
    expect(canViewSystemHealth(check)).toBe(false);
  });

  it('allows health for every authority accepted by the backend endpoint', () => {
    expect(canViewSystemHealth(has('ADMIN_SERVER_CONFIG'))).toBe(true);
    expect(canViewSystemHealth(has('ADMIN_USERS_MANAGE'))).toBe(true);
    expect(canViewSystemHealth(has('ADMIN_GROUPS_MANAGE'))).toBe(true);
  });

  it('matches the dedicated quota endpoint authorities', () => {
    expect(canManageQuotas(has('ADMIN_QUOTAS_MANAGE'))).toBe(true);
    expect(canManageQuotas(has('ADMIN_USERS_MANAGE'))).toBe(true);
    expect(canManageQuotas(has('ADMIN_GROUPS_MANAGE'))).toBe(false);
  });

  it('supports company and own-unit analytics scopes', () => {
    expect(canViewAnalytics(has('ANALYTICS_VIEW_COMPANY'))).toBe(true);
    expect(canViewAnalytics(has('ANALYTICS_VIEW_OWN_UNIT'))).toBe(true);
    expect(canViewAnalytics(has('AUDIT_VIEW'))).toBe(false);
  });
});

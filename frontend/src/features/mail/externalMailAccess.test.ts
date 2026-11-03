import { describe, expect, it } from 'vitest';
import { canAdministerExternalMail, canUseExternalMail } from './externalMailAccess';

describe('external mail access', () => {
  it('lets an employee use an assigned external address without administering connections', () => {
    const permissions = ['MAIL_READ_OWN', 'MAIL_EXTERNAL_ACCOUNTS'];

    expect(canUseExternalMail(permissions)).toBe(true);
    expect(canAdministerExternalMail(permissions)).toBe(false);
  });

  it('lets the mail administrator connect external mailboxes to employees', () => {
    const permissions = ['ADMIN_MAIL_POLICY'];

    expect(canUseExternalMail(permissions)).toBe(true);
    expect(canAdministerExternalMail(permissions)).toBe(true);
  });

  it('supports the super-administrator wildcard and rejects unrelated permissions', () => {
    expect(canAdministerExternalMail(['*'])).toBe(true);
    expect(canUseExternalMail(['*'])).toBe(true);
    expect(canUseExternalMail(['MAIL_READ_OWN'])).toBe(false);
    expect(canAdministerExternalMail(undefined)).toBe(false);
  });
});

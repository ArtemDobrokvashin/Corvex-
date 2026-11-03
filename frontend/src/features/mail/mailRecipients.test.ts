import { describe, expect, it } from 'vitest';
import { formatMailRecipients } from './mailRecipients';

const fallbacks = { loading: 'Загружаем получателей…', empty: 'Получатель не указан' };

describe('formatMailRecipients', () => {
  it('shows the actual current user name instead of a generic to-me label', () => {
    expect(formatMailRecipients(
      ['admin@company.local'],
      { email: 'ADMIN@company.local', displayName: 'Системный администратор' },
      fallbacks,
    )).toBe('Системный администратор');
  });

  it('keeps recipient names from the message and falls back to real addresses', () => {
    expect(formatMailRecipients(
      ['Анна Петрова <petrova@company.local>', 'external@example.test'],
      { email: 'admin@company.local', displayName: 'Admin' },
      fallbacks,
    )).toBe('Анна Петрова, external@example.test');
  });

  it('supports several recipients without duplicates', () => {
    expect(formatMailRecipients(
      ['Admin <admin@company.local>', 'admin@company.local'],
      { email: 'admin@company.local', displayName: 'Admin' },
      fallbacks,
    )).toBe('Admin');
  });

  it('does not invent a recipient while loading or for a Bcc-only message', () => {
    expect(formatMailRecipients(undefined, {}, fallbacks)).toBe(fallbacks.loading);
    expect(formatMailRecipients([], {}, fallbacks)).toBe(fallbacks.empty);
  });
});

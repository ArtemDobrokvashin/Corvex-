import { describe, expect, it } from 'vitest';
import { emailFromHeader } from '../../components/ui';
import { formatMailRecipients, replyAllCc } from './mailRecipients';

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

describe('reply addresses', () => {
  it('extracts both bare and named sender addresses without treating a name as an address', () => {
    expect(emailFromHeader(' alice@example.com ')).toBe('alice@example.com');
    expect(emailFromHeader('Alice <alice@example.com>')).toBe('alice@example.com');
    expect(emailFromHeader('Alice')).toBe('');
  });

  it('uses original To and Cc addresses, excluding self, sender, duplicates and display-only labels', () => {
    expect(replyAllCc(
      'Alice <alice@example.com>',
      ['Me <me@example.com>', 'Bob <bob@example.com>', 'alice@example.com'],
      ['BOB@example.com', 'Carol <carol@example.com>', 'Display name'],
      'ME@example.com',
    )).toBe('bob@example.com, carol@example.com');
  });
});

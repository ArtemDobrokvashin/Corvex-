// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { bodyWithSignature, DEFAULT_MAIL_PREFERENCES, loadMailPreferences, mailPreferencesKey, normalizeMailPreferences, saveMailPreferences } from './mailPreferences';
import { folderNameProblem } from './CreateFolderModal';

afterEach(() => { localStorage.clear(); vi.restoreAllMocks(); });

describe('mail preferences', () => {
  it('stores settings separately by user and demo mode and tolerates bad storage', () => {
    const a = mailPreferencesKey('a', false)!;
    const b = mailPreferencesKey('b', false)!;
    const demo = mailPreferencesKey('a', true)!;
    expect(saveMailPreferences(a, { ...DEFAULT_MAIL_PREFERENCES, signature: 'Alice', markRead: 'manual' })).toBe(true);
    expect(loadMailPreferences(a)).toMatchObject({ signature: 'Alice', markRead: 'manual' });
    expect(loadMailPreferences(b)).toEqual(DEFAULT_MAIL_PREFERENCES);
    expect(loadMailPreferences(demo)).toEqual(DEFAULT_MAIL_PREFERENCES);
    localStorage.setItem(b, '{bad');
    expect(loadMailPreferences(b)).toEqual(DEFAULT_MAIL_PREFERENCES);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(saveMailPreferences(b, DEFAULT_MAIL_PREFERENCES)).toBe(false);
  });

  it('normalizes invalid values and inserts an editable signature once in the correct position', () => {
    expect(normalizeMailPreferences({ density: 'bad', markRead: 'bad', defaultSort: 'bad', signature: 1 })).toEqual(DEFAULT_MAIL_PREFERENCES);
    const preferences = { ...DEFAULT_MAIL_PREFERENCES, signature: 'Regards,\nAlice', signatureForReplies: true };
    expect(bodyWithSignature('', preferences, 'new')).toBe('\n\nRegards,\nAlice');
    expect(bodyWithSignature('Hello', preferences, 'reply')).toBe('Hello\n\nRegards,\nAlice');
    expect(bodyWithSignature('\n\n--- forwarded ---\nOriginal', preferences, 'forward')).toBe('\n\nRegards,\nAlice\n\n--- forwarded ---\nOriginal');
    expect(bodyWithSignature('', { ...preferences, signatureForReplies: false }, 'reply')).toBe('');
  });
});

describe('folder name validation', () => {
  it('matches server restrictions on reserved names and path separators', () => {
    expect(folderNameProblem('  ')).toBe('empty');
    expect(folderNameProblem(' inbox ')).toBe('reserved');
    for (const name of ['.', 'a/b', 'a\\b', 'a%z', 'a*z', 'a..b', 'a\nb', 'x'.repeat(257)]) expect(folderNameProblem(name)).toBe('invalid');
    expect(folderNameProblem('x'.repeat(256))).toBe('too-long');
    expect(folderNameProblem('x'.repeat(255))).toBeNull();
    expect(folderNameProblem('💙'.repeat(128))).toBeNull();
    expect(folderNameProblem(' Проекты 2026 ')).toBeNull();
  });
});

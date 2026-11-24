// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearOfflineSnapshots,
  loadOfflineSnapshot,
  offlineBannerText,
  offlineSnapshotAge,
  saveOfflineSnapshot,
} from './offlineCache';

/**
 * Офлайн-снимок — это личные письма на устройстве. Тесты фиксируют три
 * обещания: чужому пользователю данные не отдаются, старый снимок не живёт
 * вечно, выход из аккаунта стирает всё.
 */
describe('offline mail snapshot', () => {
  const now = Date.UTC(2026, 10, 20, 10, 0, 0);

  beforeEach(() => {
    localStorage.clear();
  });

  it('returns the snapshot to its owner', () => {
    expect(saveOfflineSnapshot('me', 'folder:INBOX', [{ id: 'm1' }], now)).toBe(true);
    expect(loadOfflineSnapshot('me', 'folder:INBOX', now + 1000)).toEqual([{ id: 'm1' }]);
  });

  it('does not leak one user\u2019s mail to another', () => {
    saveOfflineSnapshot('me', 'folder:INBOX', [{ id: 'm1' }], now);
    expect(loadOfflineSnapshot('someone-else', 'folder:INBOX', now)).toBeNull();
  });

  it('drops a snapshot that is a week old', () => {
    saveOfflineSnapshot('me', 'folder:INBOX', [{ id: 'm1' }], now);
    const eightDays = now + 8 * 24 * 60 * 60 * 1000;
    expect(loadOfflineSnapshot('me', 'folder:INBOX', eightDays)).toBeNull();
    // Просроченный снимок не просто игнорируется — он удаляется с диска.
    expect(localStorage.getItem('corvex.mail.offline.folder:INBOX')).toBeNull();
  });

  it('reports how old the copy is for the banner', () => {
    saveOfflineSnapshot('me', 'folder:INBOX', [], now);
    expect(offlineSnapshotAge('me', 'folder:INBOX', now + 5 * 60_000)).toBe(5 * 60_000);
    expect(offlineSnapshotAge('someone-else', 'folder:INBOX', now)).toBeNull();
  });

  it('clears every snapshot on sign-out', () => {
    saveOfflineSnapshot('me', 'folder:INBOX', [], now);
    saveOfflineSnapshot('me', 'folder:SENT', [], now);
    clearOfflineSnapshots();
    expect(loadOfflineSnapshot('me', 'folder:INBOX', now)).toBeNull();
    expect(loadOfflineSnapshot('me', 'folder:SENT', now)).toBeNull();
  });

  it('phrases the banner honestly in both languages', () => {
    const savedAt = Date.now() - 2 * 60 * 60 * 1000;
    expect(offlineBannerText(savedAt, 'ru')).toContain('2 ч');
    expect(offlineBannerText(savedAt, 'en')).toContain('2 h');
    expect(offlineBannerText(Date.now(), 'ru')).toContain('Нет сети');
    expect(offlineBannerText(Date.now(), 'en')).toContain('No connection');
  });
});

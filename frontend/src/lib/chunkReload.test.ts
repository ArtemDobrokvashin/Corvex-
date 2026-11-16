// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  isChunkLoadError,
  reloadForStaleChunk,
  shouldAutoReload,
  STALE_CHUNK_COOLDOWN_MS,
  STALE_CHUNK_STORAGE_KEY,
  tryReloadForStaleChunk,
} from './chunkReload';

describe('протухшие чанки после передеплоя', () => {
  it('распознаёт ошибку динамического import в Chrome, Safari и Firefox', () => {
    expect(isChunkLoadError(new TypeError(
      'Failed to fetch dynamically imported module: https://mail.company.local/assets/CalendarPage-CmN8WYl1.js',
    ))).toBe(true);
    expect(isChunkLoadError(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isChunkLoadError(new Error('error loading dynamically imported module'))).toBe(true);
    expect(isChunkLoadError('Failed to fetch dynamically imported module')).toBe(true);
  });

  it('не считает сетевую или обычную ошибку протухшим чанком', () => {
    expect(isChunkLoadError(new Error('NetworkError when attempting to fetch resource.'))).toBe(false);
    expect(isChunkLoadError(new Error('Cannot read properties of undefined'))).toBe(false);
    expect(isChunkLoadError(null)).toBe(false);
    expect(isChunkLoadError(undefined)).toBe(false);
  });

  it('разрешает перезагрузку только после кулдауна', () => {
    expect(shouldAutoReload(null, 1_000)).toBe(true);
    expect(shouldAutoReload(Number.NaN, 1_000)).toBe(true);
    expect(shouldAutoReload(1_000, 1_000 + STALE_CHUNK_COOLDOWN_MS)).toBe(false);
    expect(shouldAutoReload(1_000, 1_000 + STALE_CHUNK_COOLDOWN_MS + 1)).toBe(true);
  });

  it('перезагружает один раз и запоминает попытку', () => {
    const write = vi.fn();
    const reload = vi.fn();

    const result = reloadForStaleChunk({ read: () => null, write, reload, now: 5_000, online: true });

    expect(result).toBe('reloaded');
    expect(write).toHaveBeenCalledWith(5_000);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('не уходит в цикл перезагрузок и не перезагружается без сети', () => {
    const reload = vi.fn();
    const write = vi.fn();

    expect(reloadForStaleChunk({ read: () => 5_000, write, reload, now: 6_000, online: true })).toBe('cooldown');
    expect(reloadForStaleChunk({ read: () => null, write, reload, now: 5_000, online: false })).toBe('offline');
    expect(write).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
  });

  it('в браузере уважает сохранённый кулдаун и отсутствие сети', () => {
    sessionStorage.setItem(STALE_CHUNK_STORAGE_KEY, String(Date.now()));
    expect(tryReloadForStaleChunk()).toBe('cooldown');

    sessionStorage.clear();
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    expect(tryReloadForStaleChunk()).toBe('offline');
    online.mockRestore();
  });

  beforeEach(() => {
    sessionStorage.clear();
  });
});

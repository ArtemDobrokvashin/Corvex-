/**
 * После передеплоя старый index.html (или уже открытая вкладка) ссылается на
 * чанки с прежними хешами. Их больше нет на сервере, динамический import падает,
 * и без обработки пользователь видит технический экран React Router.
 *
 * Лечение — одна автоматическая перезагрузка страницы: она заново скачивает
 * index.html и актуальный список чанков. Повторная перезагрузка в пределах
 * кулдауна запрещена, иначе при недоступном сервере получится бесконечный цикл.
 */

const STALE_CHUNK_PATTERN =
  /failed to fetch dynamically imported module|error loading dynamically imported module|importing a module script failed|dynamically imported module/i;

export const STALE_CHUNK_STORAGE_KEY = 'corvex.stale-chunk-reload';
export const STALE_CHUNK_COOLDOWN_MS = 15_000;

/** Ошибки загрузки чанка приходят от Chrome, Safari и Firefox разными словами. */
export function isChunkLoadError(error: unknown): boolean {
  if (!error) return false;
  if (typeof error === 'string') return STALE_CHUNK_PATTERN.test(error);
  const message = error instanceof Error ? error.message : '';
  return STALE_CHUNK_PATTERN.test(message);
}

export function shouldAutoReload(
  lastAttempt: number | null,
  now: number,
  cooldownMs: number = STALE_CHUNK_COOLDOWN_MS,
): boolean {
  if (lastAttempt === null || !Number.isFinite(lastAttempt)) return true;
  return now - lastAttempt > cooldownMs;
}

export type StaleChunkReloadResult = 'reloaded' | 'cooldown' | 'offline' | 'unavailable';

export interface StaleChunkEnvironment {
  read: () => number | null;
  write: (value: number) => void;
  reload: () => void;
  now?: number;
  online?: boolean;
}

/** Чистая логика решения: её проверяют тесты, браузерные обёртки — ниже. */
export function reloadForStaleChunk(environment: StaleChunkEnvironment): StaleChunkReloadResult {
  if (environment.online === false) return 'offline';
  const now = environment.now ?? Date.now();
  if (!shouldAutoReload(environment.read(), now)) return 'cooldown';
  environment.write(now);
  environment.reload();
  return 'reloaded';
}

function safeSessionStorage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

/**
 * Без sessionStorage нельзя запомнить попытку, поэтому авто-перезагрузка
 * отключается: лучше показать экран с кнопкой, чем уйти в цикл.
 */
export function tryReloadForStaleChunk(): StaleChunkReloadResult {
  if (typeof window === 'undefined') return 'unavailable';
  const storage = safeSessionStorage();
  if (!storage) return 'unavailable';
  return reloadForStaleChunk({
    read: () => {
      try {
        const raw = storage.getItem(STALE_CHUNK_STORAGE_KEY);
        const value = raw === null ? Number.NaN : Number(raw);
        return Number.isFinite(value) ? value : null;
      } catch {
        return null;
      }
    },
    write: (value) => {
      try {
        storage.setItem(STALE_CHUNK_STORAGE_KEY, String(value));
      } catch {
        // Перезагрузка всё равно может помочь: свежий index.html придёт по сети.
      }
    },
    reload: () => window.location.reload(),
    online: typeof navigator === 'undefined' ? undefined : navigator.onLine,
  });
}

/** Ручное «Обновить страницу» сбрасывает кулдаун: человек сам попросил перезагрузку. */
export function resetStaleChunkGuard(): void {
  try {
    safeSessionStorage()?.removeItem(STALE_CHUNK_STORAGE_KEY);
  } catch {
    // Storage может быть недоступен — перезагрузка сработает и без сброса.
  }
}

export function reloadPage(): void {
  if (typeof window !== 'undefined') window.location.reload();
}

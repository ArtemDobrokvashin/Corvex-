/**
 * Последний снимок данных для чтения без сети.
 *
 * Осознанно это НЕ service worker и не кэш «всего приложения»: письма — личные
 * данные, и складывать их в Cache Storage без срока жизни опаснее, чем показать
 * «нет сети». Здесь только то, что уже видел владелец на этом устройстве:
 * список писем по папкам и последние открытые тексты. Снимок живёт ограниченное
 * время и стирается при выходе из аккаунта.
 *
 * Зачем: в метро и в самолёте веб-почта раньше показывала пустой экран с
 * ошибкой сети. У Gmail и Outlook в такой ситуации открывается последний
 * синхронизированный вид.
 */
const PREFIX = 'corvex.mail.offline.';
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
/** Потолок на пользователя: 2 МиБ, чтобы не забить localStorage. */
const MAX_BYTES = 2 * 1024 * 1024;

interface Envelope<T> {
  savedAt: number;
  owner: string;
  data: T;
}

function key(name: string): string {
  return `${PREFIX}${name}`;
}

function available(): Storage | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function approximateBytes(): number {
  const storage = available();
  if (!storage) return 0;
  let total = 0;
  for (let index = 0; index < storage.length; index += 1) {
    const name = storage.key(index);
    if (name?.startsWith(PREFIX)) total += (storage.getItem(name)?.length ?? 0) * 2;
  }
  return total;
}

/** Сохраняет снимок. Возвращает false, если места нет: почта должна работать и без кэша. */
export function saveOfflineSnapshot<T>(owner: string, name: string, data: T, now = Date.now()): boolean {
  const storage = available();
  if (!storage || !owner) return false;
  const payload = JSON.stringify({ savedAt: now, owner, data } satisfies Envelope<T>);
  try {
    if (approximateBytes() + payload.length * 2 > MAX_BYTES) return false;
    storage.setItem(key(name), payload);
    return true;
  } catch {
    // Переполнение квоты: чтение без сети — приятный бонус, а не условие работы.
    return false;
  }
}

/** Читает снимок только для того же пользователя и только свежий. */
export function loadOfflineSnapshot<T>(owner: string, name: string, now = Date.now()): T | null {
  const storage = available();
  if (!storage || !owner) return null;
  try {
    const raw = storage.getItem(key(name));
    if (!raw) return null;
    const envelope = JSON.parse(raw) as Envelope<T>;
    if (envelope.owner !== owner) return null;
    if (!Number.isFinite(envelope.savedAt) || now - envelope.savedAt > MAX_AGE_MS) {
      storage.removeItem(key(name));
      return null;
    }
    return envelope.data;
  } catch {
    return null;
  }
}

/** Сколько времени снимку — для честной подписи «данные на 10:42». */
export function offlineSnapshotAge(owner: string, name: string, now = Date.now()): number | null {
  const storage = available();
  if (!storage || !owner) return null;
  try {
    const raw = storage.getItem(key(name));
    if (!raw) return null;
    const envelope = JSON.parse(raw) as Envelope<unknown>;
    if (envelope.owner !== owner || !Number.isFinite(envelope.savedAt)) return null;
    return Math.max(0, now - envelope.savedAt);
  } catch {
    return null;
  }
}

/** Выход из аккаунта: чужие письма не должны оставаться на устройстве. */
export function clearOfflineSnapshots(): void {
  const storage = available();
  if (!storage) return;
  const names: string[] = [];
  for (let index = 0; index < storage.length; index += 1) {
    const name = storage.key(index);
    if (name?.startsWith(PREFIX)) names.push(name);
  }
  for (const name of names) storage.removeItem(name);
}

/** Офлайн-подпись в списке: человек видит, что читает снимок, а не свежие данные. */
export function offlineBannerText(savedAt: number, locale: 'ru' | 'en'): string {
  const minutes = Math.max(0, Math.round((Date.now() - savedAt) / 60000));
  if (locale === 'en') {
    if (minutes < 1) return 'No connection. Showing the copy saved just now.';
    if (minutes < 60) return `No connection. Showing the copy saved ${minutes} min ago.`;
    const hours = Math.round(minutes / 60);
    return hours < 24
      ? `No connection. Showing the copy saved ${hours} h ago.`
      : `No connection. Showing the copy saved ${Math.round(hours / 24)} days ago.`;
  }
  if (minutes < 1) return 'Нет сети. Показана копия, сохранённая только что.';
  if (minutes < 60) return `Нет сети. Показана копия, сохранённая ${minutes} мин назад.`;
  const hours = Math.round(minutes / 60);
  return hours < 24
    ? `Нет сети. Показана копия, сохранённая ${hours} ч назад.`
    : `Нет сети. Показана копия, сохранённая ${Math.round(hours / 24)} дн. назад.`;
}

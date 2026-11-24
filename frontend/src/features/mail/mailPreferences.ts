import type { MailSortOrder } from './mailListControls';

/**
 * Browser-only preferences. Серверного хранения пока нет: подпись, плотность
 * и уведомления живут в `localStorage` этого браузера и на другое устройство
 * не переезжают. Это ограничение честно написано в окне настроек.
 */
export interface MailPreferences {
  density: 'comfortable' | 'compact';
  showPreview: boolean;
  /** Цепочки писем в списке. Выключено — плоский список, как было до P2-1. */
  conversations: boolean;
  markRead: 'automatic' | 'manual';
  defaultSort: MailSortOrder;
  signature: string;
  signatureForNew: boolean;
  signatureForReplies: boolean;
  notifications: boolean;
}

export const DEFAULT_MAIL_PREFERENCES: MailPreferences = {
  density: 'comfortable',
  showPreview: true,
  conversations: true,
  markRead: 'automatic',
  defaultSort: 'date-desc',
  signature: '',
  signatureForNew: true,
  signatureForReplies: false,
  notifications: false,
};

export function mailPreferencesKey(userId: string | null | undefined, demo: boolean): string | null {
  return userId ? `corvex.mail.preferences:${demo ? 'demo' : 'user'}:${encodeURIComponent(userId)}` : null;
}

export function normalizeMailPreferences(value: unknown): MailPreferences {
  const v = value && typeof value === 'object' ? value as Partial<MailPreferences> : {};
  // `unread-first` появился позже: старые сохранённые настройки без него
  // продолжают читаться, а новый порядок не сбрасывается в «сначала новые».
  const sortOrders: MailSortOrder[] = ['date-desc', 'date-asc', 'sender-asc', 'subject-asc', 'unread-first'];
  return {
    density: v.density === 'compact' ? 'compact' : 'comfortable',
    showPreview: typeof v.showPreview === 'boolean' ? v.showPreview : true,
    // По умолчанию включено: у Gmail и Outlook переписка собрана, и человек
    // ждёт того же. Кому нужен плоский список — снимает галочку в настройках.
    conversations: typeof v.conversations === 'boolean' ? v.conversations : true,
    markRead: v.markRead === 'manual' ? 'manual' : 'automatic',
    defaultSort: sortOrders.includes(v.defaultSort as MailSortOrder) ? v.defaultSort as MailSortOrder : 'date-desc',
    signature: typeof v.signature === 'string' ? v.signature.slice(0, 5000) : '',
    signatureForNew: typeof v.signatureForNew === 'boolean' ? v.signatureForNew : true,
    signatureForReplies: typeof v.signatureForReplies === 'boolean' ? v.signatureForReplies : false,
    notifications: typeof v.notifications === 'boolean' ? v.notifications : false,
  };
}

export function loadMailPreferences(key: string | null): MailPreferences {
  try {
    if (key && typeof window !== 'undefined') {
      const raw = window.localStorage.getItem(key);
      if (raw) return normalizeMailPreferences(JSON.parse(raw));
    }
  } catch {
    // Storage may be blocked, full, or contain malformed data.
  }
  return { ...DEFAULT_MAIL_PREFERENCES };
}

/** Returns false when preferences cannot be stored, so the UI can warn the user. */
export function saveMailPreferences(key: string | null, value: MailPreferences): boolean {
  try {
    if (!key || typeof window === 'undefined') return false;
    window.localStorage.setItem(key, JSON.stringify(normalizeMailPreferences(value)));
    return true;
  } catch {
    return false;
  }
}

/** Signature is inserted into the editable body; it is never appended again at send time. */
export function bodyWithSignature(body: string, preferences: MailPreferences, kind: 'new' | 'reply' | 'forward'): string {
  const include = kind === 'new' ? preferences.signatureForNew : preferences.signatureForReplies;
  const signature = preferences.signature.trim();
  if (!include || !signature) return body;
  if (kind === 'forward') return `\n\n${signature}${body}`;
  return body ? `${body}\n\n${signature}` : `\n\n${signature}`;
}

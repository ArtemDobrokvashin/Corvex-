import { cleanPersonName, emailFromHeader } from '../../components/ui';

interface RecipientIdentity {
  email?: string;
  displayName?: string;
}

interface RecipientFallbacks {
  loading: string;
  empty: string;
}

/** Preserve actual addresses, not display labels, when composing a reply-all. */
export function replyAllCc(
  sender: string,
  originalTo: readonly string[],
  originalCc: readonly string[],
  currentEmail?: string,
): string {
  const seen = new Set([emailFromHeader(sender), currentEmail?.trim() ?? ''].filter(Boolean).map((value) => value.toLowerCase()));
  const result: string[] = [];
  for (const value of [...originalTo, ...originalCc]) {
    const address = emailFromHeader(value);
    if (!/^[^\s<>@]+@[^\s<>@]+$/.test(address)) continue;
    const key = address.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(address);
  }
  return result.join(', ');
}

/**
 * Формирует видимый список получателей без подмены его на «мне».
 * API может вернуть либо адрес, либо RFC-строку с именем; для своего адреса
 * предпочтителен актуальный displayName из session, а не жёсткая локализованная заглушка.
 */
export function formatMailRecipients(
  recipients: readonly string[] | undefined,
  current: RecipientIdentity,
  fallbacks: RecipientFallbacks,
): string {
  if (recipients === undefined) return fallbacks.loading;
  const currentEmail = current.email?.trim().toLowerCase();
  const currentName = current.displayName?.trim();
  const formatted = recipients.map((raw) => {
    const value = raw.trim();
    if (!value) return '';
    const address = (emailFromHeader(value) || value).trim();
    if (currentEmail && address.toLowerCase() === currentEmail && currentName) {
      return currentName;
    }
    const displayName = cleanPersonName(value);
    return displayName && (displayName !== value || address === value) ? displayName : address;
  }).filter(Boolean);
  return [...new Set(formatted)].join(', ') || fallbacks.empty;
}

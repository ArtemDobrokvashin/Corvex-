import { cleanPersonName, emailFromHeader } from '../../components/ui';

interface RecipientIdentity {
  email?: string;
  displayName?: string;
}

interface RecipientFallbacks {
  loading: string;
  empty: string;
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

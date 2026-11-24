export interface ReplySource {
  id: string;
  from: string;
  subject: string;
  date: string;
  body: string;
  to?: string[];
  cc?: string[];
}

/**
 * Тело ответа с цитатой исходного письма.
 *
 * Раньше ответ открывался с пустым телом: получатель видел «Re: …» без контекста
 * и не мог понять, на что отвечают. Цитата — обычная текстовая, как в почтовых
 * клиентах по умолчанию: она остаётся читаемой в любом клиенте и в plain-text.
 *
 * `headerLabel` и `wroteLabel` приходят локализованными: функция не знает про i18n.
 */
export function buildReplyBody(
  source: ReplySource,
  options: {
    senderLabel: string;
    wroteLabel: string;
    /** Подпись пользователя добавляется отдельно, чтобы не попасть внутрь цитаты. */
    maxBodyLength?: number;
  },
): string {
  const limit = options.maxBodyLength ?? 20_000;
  const quoted = source.body.trim().slice(0, limit);
  if (!quoted) return '';
  const quotedLines = quoted.split('\n').map((line) => (line ? `> ${line}` : '>')).join('\n');
  return `\n\n--- ${source.date}, ${options.senderLabel} — ${source.from} ${options.wroteLabel} ---\n${quotedLines}\n`;
}

/** Тема ответа без второго «Re:». */
export function replySubject(subject: string): string {
  const trimmed = subject.trim();
  if (/^(re|aw|fw|fwd)\s*:/i.test(trimmed)) return trimmed;
  return trimmed ? `Re: ${trimmed}` : 'Re:';
}

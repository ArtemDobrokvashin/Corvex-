import type { DemoMail } from '../../api/demoData';

/**
 * Цепочки писем (conversation view).
 *
 * Сервер не хранит ссылки `In-Reply-To`/`References` как отдельные поля: в
 * `message_id_header` лежит только собственный идентификатор письма. Поэтому
 * группировка строится на том, что видно в списке: тема без префиксов `Re:`/`Fwd:`
 * и нормализованный адрес отправителя. Правило узкое и объяснимое:
 *
 *   * письма с разными темами в одну цепочку не попадают никогда;
 *   * цепочка из одного письма рисуется как обычное письмо (без «N писем»);
 *   * порядок внутри цепочки — по времени, от старых к новым, как в Gmail;
 *   * `непрочитанных` считаются по письмам, а не по цепочкам, чтобы счётчик
 *     слева совпадал со списком.
 *
 * Группировка только по теме была бы ложной («Re: договор» у двух разных
 * контрагентов — разные переписки), поэтому адрес отправителя обязателен.
 * Обратная сторона: переписка, в которой каждый ответ пишет новый человек,
 * распадётся на несколько цепочек. Это честнее, чем склеить чужие письма.
 */

/** Убирает служебные префиксы ответа/пересылки, сохраняя остальную тему. */
export function conversationSubject(subject: string): string {
  let value = (subject ?? '').trim();
  // Повторные `Re: Re: Re:` и локализованные `Ответ:`/`Пересл:` — обычное дело.
  const prefix = /^(?:\s*(?:re|fwd|fw|aw|antw|ответ|пересл)\s*(?:\[\d+\])?\s*:\s*)+/i;
  while (prefix.test(value)) {
    const next = value.replace(prefix, '');
    if (next === value) break;
    value = next;
  }
  return value.trim().toLocaleLowerCase();
}

/** Адрес из строки вида `Имя <addr@host>`; при отсутствии углов — вся строка. */
export function conversationAddress(from: string): string {
  const raw = (from ?? '').trim();
  const angled = raw.match(/<([^>]+)>/);
  const address = (angled ? angled[1] : raw).trim().toLocaleLowerCase();
  return address;
}

export function conversationKey(mail: Pick<DemoMail, 'subject' | 'from'>): string {
  return `${conversationAddress(mail.from)}\u0000${conversationSubject(mail.subject)}`;
}

export interface Conversation<M extends Pick<DemoMail, 'subject' | 'from' | 'timestamp' | 'seen'>> {
  key: string;
  /** Письма цепочки, старые сверху: в раскрытом виде читаются как переписка. */
  mails: M[];
  /** Тема в том виде, в котором её видно в списке (без префиксов). */
  subject: string;
  latest: M;
  unread: number;
  /** Сколько участников, кроме владельца ящика, — по различию адресов. */
  participants: number;
}

/**
 * Собирает цепочки, сохраняя порядок первого появления в исходном списке.
 * Исходный массив не меняется.
 */
export function groupIntoConversations<M extends Pick<DemoMail, 'subject' | 'from' | 'timestamp' | 'seen'>>(
  mails: readonly M[],
): Conversation<M>[] {
  const byKey = new Map<string, M[]>();
  const order: string[] = [];
  for (const mail of mails) {
    const key = conversationKey(mail);
    const bucket = byKey.get(key);
    if (bucket) {
      bucket.push(mail);
    } else {
      byKey.set(key, [mail]);
      order.push(key);
    }
  }

  return order.map((key) => {
    const group = [...byKey.get(key)!].sort((left, right) => left.timestamp - right.timestamp);
    const addresses = new Set(group.map((mail) => conversationAddress(mail.from)));
    return {
      key,
      mails: group,
      subject: stripSubjectPrefixes(group[group.length - 1].subject),
      latest: group[group.length - 1],
      unread: group.filter((mail) => !mail.seen).length,
      participants: addresses.size,
    };
  });
}

/** Тема для показа: префиксы сняты, но регистр и текст сохранены. */
export function stripSubjectPrefixes(subject: string): string {
  let value = (subject ?? '').trim();
  const prefix = /^(?:\s*(?:re|fwd|fw|aw|antw|ответ|пересл)\s*(?:\[\d+\])?\s*:\s*)+/i;
  while (prefix.test(value)) {
    const next = value.replace(prefix, '');
    if (next === value) break;
    value = next;
  }
  return value.trim();
}

/** Подпись «N писем» с правильным склонением; для одного письма — пусто. */
export function conversationCountLabel(count: number, locale: 'ru' | 'en'): string {
  if (count <= 1) return '';
  if (locale === 'en') return `${count} messages`;
  const mod10 = count % 10;
  const mod100 = count % 100;
  const word = mod10 === 1 && mod100 !== 11 ? 'письмо'
    : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? 'письма'
      : 'писем';
  return `${count} ${word}`;
}

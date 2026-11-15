import type { MailAttachmentView } from '../../api/mailApi';
import { DRAFT_LIMITS } from './drafts';

/**
 * Групповая пересылка оригиналов: тела писем объединяются, вложения переносятся
 * только те, что сервер уже признал чистыми.
 *
 * Правило безопасности важнее полноты: вложение, которое не проверено или
 * заражено, в исходящее письмо не попадает. Скачивание такого файла тоже не
 * выполняется — сервер (MailApiService) отвечает 403/503, но лишний запрос
 * не нужен, а «пусть уйдёт, вдруг чистое» недопустимо.
 */
export type ForwardBlockReason = 'infected' | 'unverified' | 'too-large' | 'too-many' | 'total-limit' | 'download-failed';

export interface ForwardCandidate {
  messageId: string;
  attachment: MailAttachmentView;
}

export interface ForwardBlocked {
  messageId: string;
  fileName: string;
  reason: ForwardBlockReason;
}

export interface ForwardPlan {
  /** Что можно скачать и приложить к новому письму. */
  candidates: ForwardCandidate[];
  /** Что осознанно не пересылается, с причиной для пользователя. */
  blocked: ForwardBlocked[];
}

/** Тот же лимит, что у серверной проверки черновика и вложения письма. */
export const FORWARD_LIMITS = DRAFT_LIMITS;

export function isAttachmentClean(attachment: MailAttachmentView): boolean {
  return attachment.avStatus === 'CLEAN';
}

/**
 * Просроченное скачивание не должно незаметно превращаться в пересылку без
 * вложения, поэтому планировщик явно возвращает список причин отказа.
 */
export function planForwardAttachments(
  messages: { id: string; attachments: MailAttachmentView[] }[],
): ForwardPlan {
  const candidates: ForwardCandidate[] = [];
  const blocked: ForwardBlocked[] = [];
  let total = 0;
  for (const message of messages) {
    for (const attachment of message.attachments) {
      if (!isAttachmentClean(attachment)) {
        blocked.push({
          messageId: message.id,
          fileName: attachment.fileName,
          reason: attachment.avStatus === 'INFECTED' ? 'infected' : 'unverified',
        });
        continue;
      }
      if (attachment.sizeBytes > FORWARD_LIMITS.maxFileBytes) {
        blocked.push({ messageId: message.id, fileName: attachment.fileName, reason: 'too-large' });
        continue;
      }
      if (candidates.length >= FORWARD_LIMITS.maxFiles) {
        blocked.push({ messageId: message.id, fileName: attachment.fileName, reason: 'too-many' });
        continue;
      }
      if (total + attachment.sizeBytes > FORWARD_LIMITS.maxTotalBytes) {
        blocked.push({ messageId: message.id, fileName: attachment.fileName, reason: 'total-limit' });
        continue;
      }
      total += attachment.sizeBytes;
      candidates.push({ messageId: message.id, attachment });
    }
  }
  return { candidates, blocked };
}

export interface ForwardedMessage {
  sender: string;
  subject: string;
  /** Уже локализованная дата из письма. */
  date: string;
  bodyText: string;
}

/**
 * Оригиналы должны читаться как оригиналы: адрес, тема, дата и полный текст
 * сохраняются дословно, без обрезки сниппетом.
 */
export function buildForwardBody(messages: ForwardedMessage[], separatorLabel: string): string {
  return messages.map((message) => [
    `--- ${separatorLabel} ---`,
    `From: ${message.sender}`,
    `Date: ${message.date}`,
    `Subject: ${message.subject}`,
    '',
    message.bodyText,
  ].join('\n')).join('\n\n');
}

/** Причина отказа → ключ перевода; UI сам решает, как это показать. */
export function forwardBlockLabelKey(reason: ForwardBlockReason): string {
  switch (reason) {
    case 'infected': return 'заражён антивирусом';
    case 'unverified': return 'не проверен антивирусом';
    case 'too-large': return 'больше 3 МБ';
    case 'too-many': return 'превышен лимит вложений';
    case 'total-limit': return 'превышен общий размер вложений';
    default: return 'не удалось скачать';
  }
}

/** Заголовок пересылки: одиночное письмо получает один префикс, пачка — количество. */
export function forwardSubject(messages: { subject: string }[], prefix: string): string {
  if (messages.length === 0) return '';
  if (messages.length === 1) return `${prefix} ${messages[0].subject}`;
  return `${prefix} (${messages.length}) ${messages[0].subject}`;
}


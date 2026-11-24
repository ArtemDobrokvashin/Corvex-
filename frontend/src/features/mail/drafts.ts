import { SendAttachment, WebmailDraftPayload } from '../../api/mailApi';

/**
 * Пределы вложений. Один источник правды для трёх мест:
 * - черновик (DraftPayload.validate) — 10 файлов, 3 МиБ на файл, 10 МиБ всего;
 * - отправка (MailSendService.validateAttachments) — 20 МиБ суммарно, 20 файлов;
 * - прокси (nginx client_max_body_size 26m) — потолок запроса.
 *
 * Раньше три числа жили порознь: клиент разрешал отправить 9 МиБ, сервер
 * принимал 8 МиБ на запрос, и человек получал «413» без объяснения.
 */
export const DRAFT_LIMITS = {
  maxFiles: 10,
  maxFileBytes: 3 * 1024 * 1024,
  maxTotalBytes: 10 * 1024 * 1024,
} as const;

/** Пределы отправки: то, что реально дойдёт до сервера через nginx. */
export const SEND_LIMITS = {
  maxFiles: 20,
  maxTotalBytes: 20 * 1024 * 1024,
  /** base64 занимает на треть больше: оставляем запас до nginx 26m. */
  requestBudgetBytes: 26 * 1024 * 1024,
} as const;

export type SendSizeProblem = 'count' | 'total' | null;

/** Проверка перед отправкой: человек узнаёт о размере до запроса, а не из «413». */
export function sendSizeProblem(files: File[]): SendSizeProblem {
  if (files.length > SEND_LIMITS.maxFiles) return 'count';
  const encoded = files.reduce((total, file) => total + Math.ceil(file.size / 3) * 4, 0);
  if (encoded > SEND_LIMITS.requestBudgetBytes) return 'total';
  return null;
}

/** Сколько ещё можно приложить — для подписи «можно приложить ещё N МБ». */
export function remainingAttachmentBytes(files: File[]): number {
  const used = files.reduce((total, file) => total + Math.ceil(file.size / 3) * 4, 0);
  return Math.max(0, SEND_LIMITS.requestBudgetBytes - used);
}

/** Файл в base64 без чтения через сеть: черновик остаётся локальным до отправки. */
export async function fileToAttachment(file: File): Promise<SendAttachment> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return {
    fileName: file.name,
    contentType: file.type || 'application/octet-stream',
    contentBase64: btoa(binary),
  };
}

/** Обратное преобразование: черновик с сервера снова становится вложением в композере. */
export function attachmentToFile(attachment: SendAttachment): File {
  const binary = atob(attachment.contentBase64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return new File([bytes], attachment.fileName, { type: attachment.contentType || 'application/octet-stream' });
}

export type DraftSizeProblem = 'count' | 'file' | 'total' | null;

/** Проверка до кодирования: экономит память и даёт понятную причину отказа. */
export function draftSizeProblem(files: File[]): DraftSizeProblem {
  if (files.length > DRAFT_LIMITS.maxFiles) return 'count';
  if (files.some((file) => file.size > DRAFT_LIMITS.maxFileBytes)) return 'file';
  if (files.reduce((total, file) => total + file.size, 0) > DRAFT_LIMITS.maxTotalBytes) return 'total';
  return null;
}

export interface DraftFields {
  to: string;
  cc?: string;
  subject: string;
  body: string;
  bodyHtml?: string | null;
  externalAccountId?: string | null;
}

export async function buildDraftPayload(fields: DraftFields, files: File[]): Promise<WebmailDraftPayload> {
  return {
    to: fields.to,
    cc: fields.cc ?? '',
    subject: fields.subject,
    bodyText: fields.body,
    bodyHtml: fields.bodyHtml ?? '',
    externalAccountId: fields.externalAccountId ?? null,
    attachments: await Promise.all(files.map(fileToAttachment)),
  };
}

/** Ключ перевода для подписи в композере; время добавляет вызывающая сторона. */
export function draftStatusKey(status: 'idle' | 'saving' | 'saved' | 'error'): string | null {
  if (status === 'saving') return 'Сохранение черновика…';
  if (status === 'saved') return 'Черновик сохранён';
  if (status === 'error') return 'Черновик не сохранён: сервер недоступен';
  return null;
}

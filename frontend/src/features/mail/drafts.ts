import { SendAttachment, WebmailDraftPayload } from '../../api/mailApi';

/** Совпадает с серверной валидацией DraftPayload. */
export const DRAFT_LIMITS = {
  maxFiles: 10,
  maxFileBytes: 3 * 1024 * 1024,
  maxTotalBytes: 10 * 1024 * 1024,
} as const;

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

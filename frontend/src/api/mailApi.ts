import { apiBlob, apiFetch } from './httpClient';

/** Контракты mail-storage-service (gateway: /api/v1/mail/**). */
export interface MailboxQuota {
  usedBytes: number;
  quotaBytes: number;
  messageCount: number;
}

export interface MailFolderView {
  id: string;
  name: string;
  specialUse: string | null;
  total: number;
  unread: number;
}

export interface MailMessageListItem {
  id: string;
  folderId: string;
  uid: number;
  sender: string;
  subject: string;
  snippet: string | null;
  date: string;
  sizeBytes: number;
  seen: boolean;
  flagged: boolean;
  answered: boolean;
  hasAttachments: boolean;
  avStatus: string;
  spamScore: number | null;
}

export interface MailAttachmentView {
  partIndex: number;
  sha256: string;
  fileName: string;
  sizeBytes: number;
  contentType: string | null;
  avStatus: string;
}

export interface MailMessageDetail {
  summary: MailMessageListItem;
  to: string[];
  cc: string[];
  bodyText: string;
  bodyHtml: string | null;
  attachments: MailAttachmentView[];
}

export interface SendAttachment {
  fileName: string;
  contentType: string;
  contentBase64: string;
}

export interface SendMessageRequest {
  to: string[];
  cc?: string[];
  bcc?: string[];
  subject: string;
  bodyText: string;
  bodyHtml?: string | null;
  attachments?: SendAttachment[];
  saveToSent?: boolean;
  externalAccountId?: string | null;
  /**
   * Письмо, на которое отвечаем. Сервер берёт из него `Message-ID` и `References`
   * и ставит заголовки `In-Reply-To`/`References` — по ним почтовики собирают тред.
   */
  inReplyToMessageId?: string | null;
  /** «Отправить позже»: время в ISO. null/undefined — отправить сразу. */
  sendAt?: string | null;
}

/** Серверный вид настроек почты (совпадает с полями интерфейса). */
export interface ServerMailSettings {
  density: 'comfortable' | 'compact';
  showPreview: boolean;
  conversations: boolean;
  markRead: 'automatic' | 'manual';
  defaultSort: 'date-desc' | 'date-asc' | 'sender-asc' | 'subject-asc' | 'unread-first';
  signature: string;
  signatureForNew: boolean;
  signatureForReplies: boolean;
  notifications: boolean;
  /** Подпись в письмах из Thunderbird/Outlook; по умолчанию выключено. */
  signatureForSubmission: boolean;
}

/** Письмо, скрытое из списка до указанного момента. */
export interface SnoozedMessage {
  messageId: string;
  subject: string;
  sender: string;
  folderId: string;
  wakeAt: string;
}

/** Отложенная отправка: ещё не ушедшее письмо. */
export interface ScheduledSend {
  id: string;
  sendAt: string;
  status: 'SCHEDULED' | 'SENDING' | 'FAILED' | string;
}

export interface SendMessageResult {
  messageIdHeader: string;
  sentMessageId: string | null;
  localRecipients: string[];
  externalRecipients: string[];
  fromAddress?: string;
  externalAccountId?: string | null;
  /** Заполнено, если письмо отложено: по этому id отправку отменяют. */
  scheduledSendId?: string | null;
  scheduledSendAt?: string | null;
}

/** Черновики: owner-scope на сервере, ревизия защищает от перезаписи из другой вкладки. */
export interface WebmailDraftPayload {
  to: string;
  cc: string;
  subject: string;
  bodyText: string;
  bodyHtml: string;
  externalAccountId: string | null;
  attachments: SendAttachment[];
}

export interface SavedDraft {
  id: string;
  revision: number;
  payload: WebmailDraftPayload;
  updatedAt: string;
}

export interface DraftSummary {
  id: string;
  revision: number;
  to: string | null;
  subject: string | null;
  attachmentCount: number;
  sizeBytes: number;
  updatedAt: string;
}

/** Результат серверного поиска: письмо и короткое превью. */
export interface MailSearchResult {
  messageId: string;
  folderId: string | null;
  uid: number;
  sender: string;
  subject: string;
  snippet: string;
  internalDate: string | null;
}

export const mailApi = {
  quota: () => apiFetch<MailboxQuota>('/api/v1/mail/quota'),
  folders: () => apiFetch<MailFolderView[]>('/api/v1/mail/folders'),
  createFolder: (name: string, parentId: string | null = null) =>
    apiFetch<MailFolderView>('/api/v1/mail/folders', {
      method: 'POST',
      body: JSON.stringify({ name, parentId }),
    }),
  deleteFolder: (folderId: string) => apiFetch<void>(`/api/v1/mail/folders/${encodeURIComponent(folderId)}`, { method: 'DELETE' }),
  messages: (folderId: string, cursorUid?: number, limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursorUid != null) params.set('cursorUid', String(cursorUid));
    return apiFetch<MailMessageListItem[]>(`/api/v1/mail/folders/${folderId}/messages?${params.toString()}`);
  },
  /**
   * Полнотекстовый поиск по всему ящику. `q` понимает Gmail-подобные фильтры
   * (`from:`, `subject:`, `is:unread`, `has:attachment`, `before:`, `after:`).
   * 503 означает «индекс недоступен»: это не пустой результат.
   */
  search: (query: string, limit = 50, signal?: AbortSignal) => {
    const params = new URLSearchParams({ q: query, limit: String(limit) });
    const path = `/api/v1/mail/search?${params.toString()}`;
    return signal ? apiFetch<MailSearchResult[]>(path, { signal }) : apiFetch<MailSearchResult[]>(path);
  },
  message: (id: string) => apiFetch<MailMessageDetail>(`/api/v1/mail/messages/${id}`),
  downloadAttachment: (id: string, partIndex: number, signal?: AbortSignal) => {
    const path = `/api/v1/mail/messages/${encodeURIComponent(id)}/attachments/${partIndex}`;
    return signal ? apiBlob(path, { signal }) : apiBlob(path);
  },
  /** PDF-предпросмотр Office-документа; 501, пока серверный конвертер выключен. */
  previewAttachment: (id: string, partIndex: number, signal?: AbortSignal) => {
    const path = `/api/v1/mail/messages/${encodeURIComponent(id)}/attachments/${partIndex}/preview`;
    return signal ? apiBlob(path, { signal }) : apiBlob(path);
  },
  flags: (id: string, flags: Partial<{ seen: boolean; flagged: boolean; answered: boolean; deleted: boolean }>) =>
    apiFetch<MailMessageListItem>(`/api/v1/mail/messages/${id}/flags`, {
      method: 'PATCH',
      body: JSON.stringify(flags),
    }),
  move: (id: string, targetFolderId: string, expected?: { folderId: string; uid: number }) =>
    apiFetch<MailMessageListItem>(`/api/v1/mail/messages/${encodeURIComponent(id)}/move`, {
      method: 'POST',
      body: JSON.stringify({ targetFolderId, ...(expected ? { expectedFolderId: expected.folderId, expectedUid: expected.uid } : {}) }),
    }),
  send: (request: SendMessageRequest) =>
    apiFetch<SendMessageResult>('/api/v1/mail/messages/send', {
      method: 'POST',
      body: JSON.stringify(request),
    }),
  /** Настройки почты на сервере: одни и те же на всех устройствах пользователя. */
  settings: () => apiFetch<ServerMailSettings>('/api/v1/mail/settings'),
  saveSettings: (settings: ServerMailSettings) =>
    apiFetch<ServerMailSettings>('/api/v1/mail/settings', {
      method: 'PUT',
      body: JSON.stringify(settings),
    }),
  /** «Отложить до…»: письмо скрыто из списка папки, но не перемещено. */
  snooze: (id: string, wakeAt: string) =>
    apiFetch<MailMessageListItem>(`/api/v1/mail/messages/${encodeURIComponent(id)}/snooze`, {
      method: 'POST',
      body: JSON.stringify({ wakeAt }),
    }),
  wake: (id: string) =>
    apiFetch<MailMessageListItem>(`/api/v1/mail/messages/${encodeURIComponent(id)}/snooze`, {
      method: 'DELETE',
    }),
  snoozed: () => apiFetch<SnoozedMessage[]>('/api/v1/mail/snoozed'),
  scheduledSends: () => apiFetch<ScheduledSend[]>('/api/v1/mail/scheduled-sends'),
  cancelScheduledSend: (id: string) =>
    apiFetch<void>(`/api/v1/mail/scheduled-sends/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  drafts: (limit = 50) => apiFetch<DraftSummary[]>(`/api/v1/mail/drafts?limit=${limit}`),
  draft: (id: string) => apiFetch<SavedDraft>(`/api/v1/mail/drafts/${encodeURIComponent(id)}`),
  createDraft: (payload: WebmailDraftPayload) =>
    apiFetch<SavedDraft>('/api/v1/mail/drafts', { method: 'POST', body: JSON.stringify(payload) }),
  updateDraft: (id: string, revision: number, payload: WebmailDraftPayload) =>
    apiFetch<SavedDraft>(`/api/v1/mail/drafts/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify({ revision, payload }),
    }),
  deleteDraft: (id: string, revision: number) =>
    apiFetch<void>(`/api/v1/mail/drafts/${encodeURIComponent(id)}?revision=${revision}`, { method: 'DELETE' }),
};

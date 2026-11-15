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
}

export interface SendMessageResult {
  messageIdHeader: string;
  sentMessageId: string | null;
  localRecipients: string[];
  externalRecipients: string[];
  fromAddress?: string;
  externalAccountId?: string | null;
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
  message: (id: string) => apiFetch<MailMessageDetail>(`/api/v1/mail/messages/${id}`),
  downloadAttachment: (id: string, partIndex: number) => apiBlob(`/api/v1/mail/messages/${encodeURIComponent(id)}/attachments/${partIndex}`),
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

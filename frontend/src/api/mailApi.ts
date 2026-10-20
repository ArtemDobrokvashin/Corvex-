import { apiFetch } from './httpClient';

/** Контракты mail-storage-service (gateway: /api/v1/mail/**). */
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
}

export interface SendMessageResult {
  messageIdHeader: string;
  sentMessageId: string | null;
  localRecipients: string[];
  externalRecipients: string[];
}

export const mailApi = {
  folders: () => apiFetch<MailFolderView[]>('/api/v1/mail/folders'),
  messages: (folderId: string, cursorUid?: number, limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursorUid != null) params.set('cursorUid', String(cursorUid));
    return apiFetch<MailMessageListItem[]>(`/api/v1/mail/folders/${folderId}/messages?${params.toString()}`);
  },
  message: (id: string) => apiFetch<MailMessageDetail>(`/api/v1/mail/messages/${id}`),
  flags: (id: string, flags: Partial<{ seen: boolean; flagged: boolean; answered: boolean; deleted: boolean }>) =>
    apiFetch<MailMessageListItem>(`/api/v1/mail/messages/${id}/flags`, {
      method: 'PATCH',
      body: JSON.stringify(flags),
    }),
  move: (id: string, targetFolderId: string) =>
    apiFetch<MailMessageListItem>(`/api/v1/mail/messages/${id}/move`, {
      method: 'POST',
      body: JSON.stringify({ targetFolderId }),
    }),
  send: (request: SendMessageRequest) =>
    apiFetch<SendMessageResult>('/api/v1/mail/messages/send', {
      method: 'POST',
      body: JSON.stringify(request),
    }),
};

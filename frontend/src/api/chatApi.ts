import { apiBlob, apiFetch } from './httpClient';

/** Контракты chat-service и chat-file-service (gateway: /api/v1/chat/**, /api/v1/files/**). */

export type ChatRoomKind = 'DIRECT' | 'GROUP' | 'CHANNEL_PUBLIC' | 'CHANNEL_PRIVATE' | 'SYSTEM';
export type ChatMemberRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'READONLY';
export type ChatMessageKind = 'TEXT' | 'FILE' | 'IMAGE' | 'VOICE' | 'VIDEO' | 'SYSTEM';

export interface DirectoryUserView {
  id: string;
  username: string;
  email: string;
  displayName: string;
  department: string | null;
  status: string;
  presence: string;
}

export interface MemberView {
  userId: string;
  role: ChatMemberRole;
  mutedUntil: string | null;
  lastReadMessageId: string | null;
  joinedAt: string;
}

export interface ReactionView {
  emoji: string;
  count: number;
  userIds: string[];
}

export interface ChatMessageView {
  id: string;
  roomId: string;
  authorId: string;
  kind: ChatMessageKind;
  bodyMarkdown: string | null;
  threadRootId: string | null;
  fileRef: string | null;
  pinned: boolean;
  editedAt: string | null;
  deleted: boolean;
  clientMessageId: string | null;
  mentionedUserIds: string[];
  reactions: ReactionView[];
  createdAt: string;
}

export interface MessagePage {
  items: ChatMessageView[];
  hasMore: boolean;
  nextBefore: string | null;
}

export interface RoomSummaryView {
  id: string;
  kind: ChatRoomKind;
  title: string | null;
  ownerId: string;
  readOnly: boolean;
  memberIds: string[];
  myRole: ChatMemberRole | null;
  memberCount: number;
  unreadCount: number;
  mentionCount: number;
  lastMessage: ChatMessageView | null;
  createdAt: string;
  lastMessageAt: string;
}

export interface ChatEventView {
  type: string;
  roomId: string;
  message: ChatMessageView | null;
  actorId: string | null;
  messageId: string | null;
  occurredAt: string;
}

export interface CreateRoomRequest {
  kind: Exclude<ChatRoomKind, 'DIRECT'>;
  title?: string | null;
  memberIds: string[];
  readOnly?: boolean;
}

export interface SendMessageRequest {
  bodyMarkdown?: string | null;
  threadRootId?: string | null;
  clientMessageId?: string | null;
  kind?: ChatMessageKind | null;
  fileRef?: string | null;
  mentionedUserIds?: string[];
}

export interface ChatFileUploadView {
  fileRef: string;
  roomId: string;
  ownerId: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  createdAt: string;
}

function query(params: Record<string, string | number | null | undefined>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value != null && value !== '') search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export function isChatEventView(value: unknown): value is ChatEventView {
  if (!value || typeof value !== 'object') return false;
  const event = value as Partial<ChatEventView>;
  return typeof event.type === 'string' && typeof event.roomId === 'string';
}

export function isChatMessageView(value: unknown): value is ChatMessageView {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<ChatMessageView>;
  return typeof message.id === 'string'
    && typeof message.roomId === 'string'
    && typeof message.authorId === 'string'
    && typeof message.createdAt === 'string';
}

export const chatApi = {
  rooms: () => apiFetch<RoomSummaryView[]>('/api/v1/chat/rooms'),

  createRoom: (request: CreateRoomRequest) => apiFetch<RoomSummaryView>('/api/v1/chat/rooms', {
    method: 'POST',
    body: JSON.stringify(request),
  }),

  direct: (userId: string) => apiFetch<RoomSummaryView>(`/api/v1/chat/rooms/direct/${userId}`, {
    method: 'POST',
  }),

  members: (roomId: string) => apiFetch<MemberView[]>(`/api/v1/chat/rooms/${roomId}/members`),

  addMembers: (roomId: string, memberIds: string[]) => apiFetch<RoomSummaryView>(
    `/api/v1/chat/rooms/${roomId}/members`,
    { method: 'POST', body: JSON.stringify({ memberIds }) },
  ),

  leave: (roomId: string) => apiFetch<void>(`/api/v1/chat/rooms/${roomId}/members/me`, {
    method: 'DELETE',
  }),

  history: (
    roomId: string,
    options: { before?: string | null; threadRootId?: string | null; limit?: number } = {},
  ) => apiFetch<MessagePage>(
    `/api/v1/chat/rooms/${roomId}/messages${query({
      before: options.before,
      threadRootId: options.threadRootId,
      limit: options.limit ?? 50,
    })}`,
  ),

  send: (roomId: string, request: SendMessageRequest) => apiFetch<ChatMessageView>(
    `/api/v1/chat/rooms/${roomId}/messages`,
    { method: 'POST', body: JSON.stringify(request) },
  ),

  edit: (messageId: string, bodyMarkdown: string) => apiFetch<ChatMessageView>(
    `/api/v1/chat/messages/${messageId}`,
    { method: 'PATCH', body: JSON.stringify({ bodyMarkdown }) },
  ),

  remove: (messageId: string) => apiFetch<ChatMessageView>(
    `/api/v1/chat/messages/${messageId}`,
    { method: 'DELETE' },
  ),

  reaction: (messageId: string, emoji: string) => apiFetch<ChatMessageView>(
    `/api/v1/chat/messages/${messageId}/reactions`,
    { method: 'PUT', body: JSON.stringify({ emoji }) },
  ),

  pin: (messageId: string, pinned: boolean) => apiFetch<ChatMessageView>(
    `/api/v1/chat/messages/${messageId}/pin`,
    { method: pinned ? 'PUT' : 'DELETE' },
  ),

  read: (roomId: string, lastReadMessageId: string) => apiFetch<void>(
    `/api/v1/chat/rooms/${roomId}/read`,
    { method: 'POST', body: JSON.stringify({ lastReadMessageId }) },
  ),

  search: (q: string, roomId?: string | null, limit = 50) => apiFetch<ChatMessageView[]>(
    `/api/v1/chat/search${query({ q, roomId, limit })}`,
  ),

  directory: (q = '', page = 0, size = 200) => apiFetch<DirectoryUserView[]>(
    `/api/v1/chat/directory${query({ q, page, size })}`,
  ),

  presence: (userIds: string[]) => {
    const params = new URLSearchParams();
    userIds.forEach((id) => params.append('userIds', id));
    return apiFetch<Record<string, string>>(`/api/v1/chat/presence?${params.toString()}`);
  },

  setPresence: (status: 'ONLINE' | 'AWAY' | 'DND') => apiFetch<void>('/api/v1/chat/presence', {
    method: 'PUT',
    body: JSON.stringify({ status }),
  }),

  uploadFile: (roomId: string, file: File) => {
    const form = new FormData();
    form.set('roomId', roomId);
    form.set('file', file);
    return apiFetch<ChatFileUploadView>('/api/v1/files', { method: 'POST', body: form });
  },

  downloadFile: (fileRef: string) => apiBlob(`/api/v1/files/${encodeURIComponent(fileRef)}`),
};

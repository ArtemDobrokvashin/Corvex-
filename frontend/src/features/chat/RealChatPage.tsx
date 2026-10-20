import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import type { Client } from '@stomp/stompjs';
import {
  ArrowLeft,
  Check,
  CheckCheck,
  Download,
  Hash,
  Loader2,
  Lock,
  LogOut,
  Megaphone,
  MoreHorizontal,
  Paperclip,
  PenSquare,
  Pin,
  PinOff,
  Reply,
  Search,
  SendHorizontal,
  Trash2,
  UserPlus,
  Users,
  X,
} from 'lucide-react';
import {
  chatApi,
  isChatEventView,
  isChatMessageView,
  type ChatMessageKind,
  type ChatMessageView,
  type ChatRoomKind,
  type DirectoryUserView,
  type MemberView,
  type RoomSummaryView,
  type SendMessageRequest,
} from '../../api/chatApi';
import { connectChat, newClientMessageId, sendChatMessage, sendRead, sendTyping } from '../../api/chatSocket';
import { ApiError, getAccessToken } from '../../api/httpClient';
import { EmojiPickerButton } from '../../components/EmojiPicker';
import { Avatar, EmptyState, IconButton, LoadingScreen, Notice } from '../../components/ui';
import {
  countLabel,
  formatDate,
  formatDateTime,
  formatTime,
  localizeApiDetail,
  useI18n,
  type Locale,
} from '../../i18n';
import { firstGraphemes, insertTextAtSelection, stripMarkdownPreview } from '../../lib/text';
import { renderChatMarkdown } from '../../lib/markdown';
import { useSession } from '../../store/sessionStore';

type Translate = ReturnType<typeof useI18n>['t'];
type LocalStatus = 'sending' | 'failed';

interface LocalMessage extends ChatMessageView {
  localStatus?: LocalStatus;
  localFileName?: string;
}

interface HistoryState {
  hasMore: boolean;
  nextBefore: string | null;
}

interface PendingSend {
  roomId: string;
  request: SendMessageRequest;
  tempId: string;
  inFlight?: boolean;
}

interface ChatErrorPayload {
  status?: number;
  detail?: string;
}

const cleanTitle = (title: string) => title.replace(/^[^A-Za-zА-Яа-я0-9]+/, '').trim();
const plainText = stripMarkdownPreview;

function compareMessages(first: ChatMessageView, second: ChatMessageView): number {
  const byTime = Date.parse(first.createdAt) - Date.parse(second.createdAt);
  return byTime !== 0 && Number.isFinite(byTime) ? byTime : first.id.localeCompare(second.id);
}

function mergeMessages(existing: LocalMessage[], incoming: LocalMessage[]): LocalMessage[] {
  const byId = new Map(existing.map((message) => [message.id, message]));
  const byClient = new Map(
    existing
      .filter((message) => message.clientMessageId)
      .map((message) => [`${message.roomId}:${message.authorId}:${message.clientMessageId}`, message]),
  );

  incoming.forEach((message) => {
    const clientKey = message.clientMessageId
      ? `${message.roomId}:${message.authorId}:${message.clientMessageId}`
      : null;
    const prior = byId.get(message.id) ?? (clientKey ? byClient.get(clientKey) : undefined);
    if (prior && prior.id !== message.id) byId.delete(prior.id);
    byId.set(message.id, {
      ...prior,
      ...message,
      localStatus: message.localStatus ?? prior?.localStatus,
      localFileName: message.localFileName ?? prior?.localFileName,
    });
    if (clientKey) byClient.set(clientKey, byId.get(message.id) as LocalMessage);
  });

  return [...byId.values()].sort(compareMessages);
}

function sortRooms(rooms: RoomSummaryView[]): RoomSummaryView[] {
  return [...rooms].sort((first, second) => {
    const firstActivity = Date.parse(first.lastMessageAt || first.createdAt);
    const secondActivity = Date.parse(second.lastMessageAt || second.createdAt);
    return (secondActivity - firstActivity) || second.id.localeCompare(first.id);
  });
}

function isErrorPayload(value: unknown): value is ChatErrorPayload {
  if (!value || typeof value !== 'object') return false;
  const payload = value as ChatErrorPayload;
  return typeof payload.detail === 'string' && !('type' in payload);
}

function sameDay(first: Date, second: Date): boolean {
  return first.getFullYear() === second.getFullYear()
    && first.getMonth() === second.getMonth()
    && first.getDate() === second.getDate();
}

function dateKey(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

function dateLabel(locale: Locale, value: string, t: Translate): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (sameDay(date, today)) return t('Сегодня');
  if (sameDay(date, yesterday)) return t('Вчера');
  return formatDate(locale, date);
}

function roomTimeLabel(locale: Locale, value: string | null | undefined, t: Translate): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return sameDay(date, new Date()) ? formatTime(locale, date) : dateLabel(locale, value, t);
}

function messagePreview(message: ChatMessageView | LocalMessage | null | undefined, t: Translate): string {
  if (!message) return '';
  if (message.deleted) return t('Сообщение удалено');
  if (message.bodyMarkdown?.trim()) return plainText(message.bodyMarkdown);
  switch (message.kind) {
    case 'IMAGE': return t('Изображение');
    case 'FILE': return ('localFileName' in message && message.localFileName) || t('Файл');
    case 'VOICE': return t('Голосовое сообщение');
    case 'VIDEO': return t('Видео');
    case 'SYSTEM': return t('Системное сообщение');
    default: return '';
  }
}

function fileNameFromRef(fileRef: string): string | null {
  try {
    const decoded = decodeURIComponent(fileRef);
    const parts = decoded.split('/').filter(Boolean);
    const name = parts[parts.length - 1] ?? '';
    return name.includes('.') ? name : null;
  } catch {
    return null;
  }
}

function FileAttachment({ message, onError }: { message: LocalMessage; onError: (message: string) => void }) {
  const { t } = useI18n();
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const fileRef = message.fileRef;
  const isImage = message.kind === 'IMAGE';

  useEffect(() => {
    if (!fileRef || !isImage) return undefined;
    let cancelled = false;
    let objectUrl = '';
    setFailed(false);
    chatApi.downloadFile(fileRef)
      .then((blob) => {
        objectUrl = URL.createObjectURL(blob);
        if (!cancelled) setImageUrl(objectUrl);
        else URL.revokeObjectURL(objectUrl);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [fileRef, isImage]);

  if (!fileRef) return null;
  const name = message.localFileName || fileNameFromRef(fileRef) || t(isImage ? 'Изображение' : 'Файл');

  const download = async () => {
    if (busy) return;
    setBusy(true);
    let temporaryUrl = '';
    try {
      const url = imageUrl ?? URL.createObjectURL(await chatApi.downloadFile(fileRef));
      if (!imageUrl) temporaryUrl = url;
      const link = document.createElement('a');
      link.href = url;
      link.download = name;
      document.body.appendChild(link);
      link.click();
      link.remove();
    } catch {
      setFailed(true);
      onError(t('Не удалось скачать вложение.'));
    } finally {
      if (temporaryUrl) window.setTimeout(() => URL.revokeObjectURL(temporaryUrl), 1_000);
      setBusy(false);
    }
  };

  return (
    <div className="mt-2 max-w-[520px]">
      {isImage && imageUrl && (
        <img
          src={imageUrl}
          alt={name}
          className="mb-2 max-h-64 w-auto max-w-full rounded-xl border border-line object-contain"
          loading="lazy"
        />
      )}
      <button
        type="button"
        onClick={() => void download()}
        disabled={busy}
        className="inline-flex max-w-full items-center gap-2 rounded-xl border border-line bg-chrome px-3 py-2 text-left text-[13px] font-medium text-ink-soft transition hover:border-accent/30 hover:bg-accent-soft disabled:cursor-wait disabled:opacity-60"
      >
        {busy ? <Loader2 size={16} className="shrink-0 animate-spin" /> : <Download size={16} className="shrink-0" />}
        <span className="min-w-0 truncate">{name}</span>
      </button>
      {failed && !imageUrl && <span className="mt-1 block text-xs text-danger">{t('Вложение сейчас недоступно.')}</span>}
    </div>
  );
}

function RealRoomMark({
  room,
  title,
  online,
  size = 40,
}: {
  room: RoomSummaryView;
  title: string;
  online?: boolean;
  size?: number;
}) {
  if (room.kind === 'DIRECT') return <Avatar name={title} size={size} presence={online} />;
  const Icon = room.kind === 'SYSTEM'
    ? Megaphone
    : room.kind === 'GROUP'
      ? Users
      : room.kind === 'CHANNEL_PRIVATE'
        ? Lock
        : Hash;
  return (
    <span
      className="flex shrink-0 items-center justify-center rounded-full bg-black/[0.055] text-muted"
      style={{ width: size, height: size }}
    >
      <Icon size={Math.round(size * 0.46)} strokeWidth={1.8} />
    </span>
  );
}

/** Настоящий persistent-чат этапа 4: REST — источник истины, STOMP — realtime-обновления. */
export default function RealChatPage() {
  const { locale, t } = useI18n();
  const user = useSession((state) => state.user);
  const has = useSession((state) => state.has);
  const currentUserId = user?.id ?? '';

  const canRead = has('CHAT_READ');
  const canWritePermission = has('CHAT_WRITE');
  const canCreateGroup = has('CHAT_CREATE_GROUP');
  const canModerateGlobally = has('CHAT_MODERATE');
  const canSendFilesPermission = has('CHAT_SEND_FILES');

  const [rooms, setRooms] = useState<RoomSummaryView[]>([]);
  const [directory, setDirectory] = useState<DirectoryUserView[]>([]);
  const [selectedRoomId, setSelectedRoomId] = useState('');
  const [messagesByRoom, setMessagesByRoom] = useState<Record<string, LocalMessage[]>>({});
  const [historyByRoom, setHistoryByRoom] = useState<Record<string, HistoryState>>({});
  const [membersByRoom, setMembersByRoom] = useState<Record<string, MemberView[]>>({});
  const [draftsByRoom, setDraftsByRoom] = useState<Record<string, string>>({});
  const [search, setSearch] = useState('');
  const [mobileConversation, setMobileConversation] = useState(false);
  const [roomsLoading, setRoomsLoading] = useState(true);
  const [historyLoadingRoomId, setHistoryLoadingRoomId] = useState('');
  const [olderLoadingRoomId, setOlderLoadingRoomId] = useState('');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [socketConnected, setSocketConnected] = useState(false);
  const [typingByRoom, setTypingByRoom] = useState<Record<string, Record<string, number>>>({});
  const [readByRoom, setReadByRoom] = useState<Record<string, Record<string, string>>>({});
  const [highlightedMessageId, setHighlightedMessageId] = useState<string | null>(null);
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [messageMenuId, setMessageMenuId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState('');
  const [pendingFiles, setPendingFiles] = useState<File[]>([]);
  const [sending, setSending] = useState(false);
  const [newChatOpen, setNewChatOpen] = useState(false);
  const [newChatMode, setNewChatMode] = useState<'DIRECT' | 'GROUP'>('DIRECT');
  const [peopleSearch, setPeopleSearch] = useState('');
  const [groupTitle, setGroupTitle] = useState('');
  const [groupMemberIds, setGroupMemberIds] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);
  const [addMemberId, setAddMemberId] = useState('');
  const [searchResults, setSearchResults] = useState<ChatMessageView[]>([]);
  const [searchingMessages, setSearchingMessages] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);

  const bottomRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const clientRef = useRef<Client | null>(null);
  const pendingRef = useRef(new Map<string, PendingSend>());
  const fallbackTimersRef = useRef(new Map<string, number>());
  const loadedRoomsRef = useRef(new Set<string>());
  const lastTypingSentRef = useRef(0);
  const lastReadSentRef = useRef<Record<string, string>>({});
  const selectedRoomIdRef = useRef('');
  const localeRef = useRef(locale);
  const directoryRef = useRef<DirectoryUserView[]>([]);

  useEffect(() => {
    selectedRoomIdRef.current = selectedRoomId;
  }, [selectedRoomId]);

  useEffect(() => {
    localeRef.current = locale;
  }, [locale]);

  useEffect(() => {
    directoryRef.current = directory;
  }, [directory]);

  const directoryById = useMemo(
    () => new Map(directory.map((person) => [person.id, person])),
    [directory],
  );

  function formatError(error: unknown): string {
    if (error instanceof ApiError) return localizeApiDetail(error.detail, locale);
    if (error instanceof Error) return localizeApiDetail(error.message, locale);
    return t('Что-то пошло не так. Попробуйте ещё раз.');
  }

  function userName(userId: string): string {
    if (userId === currentUserId) return user?.displayName ?? t('Вы');
    const person = directoryById.get(userId);
    return person?.displayName || person?.username || person?.email || t('Пользователь {id}', { id: userId.slice(0, 8) });
  }

  function authorName(message: ChatMessageView): string {
    return message.kind === 'SYSTEM' ? t('Система') : userName(message.authorId);
  }

  function presenceLabel(status: string | undefined): string {
    switch ((status ?? '').toUpperCase()) {
      case 'ONLINE': return t('В сети');
      case 'AWAY': return t('Нет на месте');
      case 'DND': return t('Не беспокоить');
      default: return t('Не в сети');
    }
  }

  function roleLabel(role: string | null | undefined): string {
    switch (role) {
      case 'OWNER': return t('Владелец');
      case 'ADMIN': return t('Администратор');
      case 'READONLY': return t('Только чтение');
      default: return t('Участник');
    }
  }

  function kindLabel(kind: ChatRoomKind): string {
    switch (kind) {
      case 'DIRECT': return t('Личный диалог');
      case 'GROUP': return t('Групповой чат');
      case 'CHANNEL_PRIVATE': return t('Приватный канал');
      case 'SYSTEM': return t('Системный канал');
      default: return t('Публичный канал');
    }
  }

  function otherDirectUser(room: RoomSummaryView): DirectoryUserView | undefined {
    const otherId = room.memberIds.find((id) => id !== currentUserId);
    return otherId ? directoryById.get(otherId) : undefined;
  }

  function roomTitle(room: RoomSummaryView): string {
    if (room.kind === 'DIRECT') {
      const otherId = room.memberIds.find((id) => id !== currentUserId);
      return otherId ? userName(otherId) : t('Личный диалог');
    }
    if (room.title?.trim()) return cleanTitle(room.title);
    if (room.kind === 'GROUP') {
      const names = room.memberIds
        .filter((id) => id !== currentUserId)
        .slice(0, 3)
        .map(userName);
      return names.length > 0 ? names.join(', ') : t('Групповой чат');
    }
    return kindLabel(room.kind);
  }

  function roomSubtitle(room: RoomSummaryView): string {
    if (room.kind === 'DIRECT') {
      const other = otherDirectUser(room);
      const presence = presenceLabel(other?.presence);
      return other?.department ? `${presence} · ${other.department}` : presence;
    }
    const members = countLabel(
      locale,
      room.memberCount,
      ['участник', 'участника', 'участников'],
      ['member', 'members'],
    );
    return `${kindLabel(room.kind)} · ${members}${room.readOnly ? ` · ${t('только чтение')}` : ''}`;
  }

  function roomPreview(room: RoomSummaryView): string {
    const roomMessages = messagesByRoom[room.id] ?? [];
    const loadedLast = roomMessages[roomMessages.length - 1];
    const last = loadedLast ?? room.lastMessage;
    if (!last) return t('Пока нет сообщений');
    const preview = messagePreview(last, t);
    if (!preview || room.kind === 'DIRECT' || last.deleted) return preview;
    return `${last.authorId === currentUserId ? t('Вы') : authorName(last)}: ${preview}`;
  }

  function updateDraft(value: string) {
    if (!selectedRoomId) return;
    setDraftsByRoom((prev) => ({ ...prev, [selectedRoomId]: value }));
  }

  function upsertMessage(message: LocalMessage) {
    setMessagesByRoom((prev) => ({
      ...prev,
      [message.roomId]: mergeMessages(prev[message.roomId] ?? [], [message]),
    }));
  }

  function upsertMessages(roomId: string, incoming: LocalMessage[]) {
    if (incoming.length === 0) return;
    setMessagesByRoom((prev) => ({
      ...prev,
      [roomId]: mergeMessages(prev[roomId] ?? [], incoming),
    }));
  }

  function upsertRoom(room: RoomSummaryView) {
    setRooms((prev) => sortRooms([room, ...prev.filter((candidate) => candidate.id !== room.id)]));
  }

  function confirmPending(message: ChatMessageView) {
    if (!message.clientMessageId || message.authorId !== currentUserId) return;
    const key = `${message.roomId}:${message.clientMessageId}`;
    const timer = fallbackTimersRef.current.get(key);
    if (timer != null) window.clearTimeout(timer);
    fallbackTimersRef.current.delete(key);
    pendingRef.current.delete(key);
  }

  function updateRoomWithMessage(message: ChatMessageView, eventType: string) {
    setRooms((prev) => {
      const existing = prev.find((room) => room.id === message.roomId);
      if (!existing) return prev;
      const isSelected = selectedRoomIdRef.current === message.roomId;
      const isMine = message.authorId === currentUserId;
      const shouldReplaceLast = !existing.lastMessage
        || compareMessages(message, existing.lastMessage) >= 0
        || existing.lastMessage.id === message.id;
      const next: RoomSummaryView = {
        ...existing,
        lastMessage: shouldReplaceLast ? message : existing.lastMessage,
        lastMessageAt: shouldReplaceLast ? message.createdAt : existing.lastMessageAt,
        unreadCount: eventType === 'MENTION' && !isSelected && !isMine
          ? existing.unreadCount + 1
          : isSelected || isMine
            ? 0
            : existing.unreadCount,
        mentionCount: eventType === 'MENTION' && !isSelected && !isMine
          ? existing.mentionCount + 1
          : isSelected || isMine
            ? 0
            : existing.mentionCount,
      };
      return sortRooms(prev.map((room) => (room.id === next.id ? next : room)));
    });
  }

  function markPendingFailed(roomId: string, tempId: string) {
    setMessagesByRoom((prev) => ({
      ...prev,
      [roomId]: (prev[roomId] ?? []).map((message) => (
        message.id === tempId ? { ...message, localStatus: 'failed' as const } : message
      )),
    }));
  }

  async function fallbackPending(key: string) {
    const pending = pendingRef.current.get(key);
    if (!pending || pending.inFlight) return;
    pending.inFlight = true;
    const timer = fallbackTimersRef.current.get(key);
    if (timer != null) window.clearTimeout(timer);
    fallbackTimersRef.current.delete(key);
    try {
      const saved = await chatApi.send(pending.roomId, pending.request);
      confirmPending(saved);
      upsertMessage({ ...saved, localStatus: undefined });
      updateRoomWithMessage(saved, 'MESSAGE_CREATED');
    } catch (error) {
      pendingRef.current.delete(key);
      markPendingFailed(pending.roomId, pending.tempId);
      setActionError(formatError(error));
    }
  }

  function dispatchSend(roomId: string, request: SendMessageRequest, tempId: string) {
    const clientMessageId = request.clientMessageId ?? newClientMessageId();
    const normalizedRequest = { ...request, clientMessageId };
    const key = `${roomId}:${clientMessageId}`;
    pendingRef.current.set(key, { roomId, request: normalizedRequest, tempId });
    const client = clientRef.current;
    if (client?.connected) {
      try {
        sendChatMessage(client, roomId, normalizedRequest.bodyMarkdown ?? null, {
          clientMessageId,
          threadRootId: normalizedRequest.threadRootId ?? null,
          kind: normalizedRequest.kind ?? null,
          fileRef: normalizedRequest.fileRef ?? null,
          mentionedUserIds: normalizedRequest.mentionedUserIds ?? [],
        });
        const timer = window.setTimeout(() => {
          void fallbackPending(key);
        }, 5_000);
        fallbackTimersRef.current.set(key, timer);
        return;
      } catch {
        // Ниже тот же идемпотентный запрос уйдёт REST-путём.
      }
    }
    void fallbackPending(key);
  }

  async function loadDirectoryPages(): Promise<DirectoryUserView[]> {
    const all: DirectoryUserView[] = [];
    const seen = new Set<string>();
    for (let page = 0; page < 5; page += 1) {
      const items = await chatApi.directory('', page, 200);
      items.forEach((person) => {
        if (!seen.has(person.id)) {
          seen.add(person.id);
          all.push(person);
        }
      });
      if (items.length < 200) break;
    }
    return all;
  }

  async function reloadRoomsOnly(silent = true) {
    try {
      const roomItems = sortRooms(await chatApi.rooms());
      setRooms(roomItems);
      setSelectedRoomId((prev) => (
        prev && roomItems.some((room) => room.id === prev) ? prev : roomItems[0]?.id ?? ''
      ));
      if (!silent) setLoadError(null);
    } catch (error) {
      if (!silent) setLoadError(formatError(error));
    }
  }

  async function loadRoomsAndDirectory() {
    if (!canRead) {
      setRoomsLoading(false);
      return;
    }
    setRoomsLoading(true);
    const [roomsResult, directoryResult] = await Promise.allSettled([
      chatApi.rooms(),
      loadDirectoryPages(),
    ]);
    try {
      if (roomsResult.status === 'rejected') throw roomsResult.reason;
      const roomItems = sortRooms(roomsResult.value);
      setRooms(roomItems);
      setSelectedRoomId((prev) => (
        prev && roomItems.some((room) => room.id === prev) ? prev : roomItems[0]?.id ?? ''
      ));
      setLoadError(null);
      if (directoryResult.status === 'fulfilled') {
        setDirectory(directoryResult.value);
      } else {
        setActionError(formatError(directoryResult.reason));
      }
    } catch (error) {
      setLoadError(formatError(error));
    } finally {
      setRoomsLoading(false);
    }
  }

  async function loadHistory(roomId: string) {
    if (!roomId || loadedRoomsRef.current.has(roomId)) return;
    loadedRoomsRef.current.add(roomId);
    setHistoryLoadingRoomId(roomId);
    try {
      const page = await chatApi.history(roomId, { limit: 50 });
      upsertMessages(roomId, page.items);
      setHistoryByRoom((prev) => ({
        ...prev,
        [roomId]: { hasMore: page.hasMore, nextBefore: page.nextBefore },
      }));
    } catch (error) {
      loadedRoomsRef.current.delete(roomId);
      setActionError(formatError(error));
    } finally {
      setHistoryLoadingRoomId((prev) => (prev === roomId ? '' : prev));
    }
  }

  async function loadOlder() {
    const roomId = selectedRoomId;
    const page = historyByRoom[roomId];
    if (!roomId || !page?.hasMore || !page.nextBefore || olderLoadingRoomId) return;
    const scroller = scrollRef.current;
    const previousHeight = scroller?.scrollHeight ?? 0;
    const previousTop = scroller?.scrollTop ?? 0;
    setOlderLoadingRoomId(roomId);
    try {
      const next = await chatApi.history(roomId, { before: page.nextBefore, limit: 50 });
      upsertMessages(roomId, next.items);
      setHistoryByRoom((prev) => ({
        ...prev,
        [roomId]: { hasMore: next.hasMore, nextBefore: next.nextBefore },
      }));
      requestAnimationFrame(() => {
        if (scroller) scroller.scrollTop = previousTop + Math.max(0, scroller.scrollHeight - previousHeight);
      });
    } catch (error) {
      setActionError(formatError(error));
    } finally {
      setOlderLoadingRoomId('');
    }
  }

  function markRead(roomId: string, messageId: string) {
    if (!roomId || !messageId || messageId.startsWith('local-')) return;
    if (lastReadSentRef.current[roomId] === messageId) return;
    lastReadSentRef.current[roomId] = messageId;
    setRooms((prev) => prev.map((room) => (
      room.id === roomId ? { ...room, unreadCount: 0, mentionCount: 0 } : room
    )));
    if (currentUserId) {
      setReadByRoom((prev) => ({
        ...prev,
        [roomId]: { ...(prev[roomId] ?? {}), [currentUserId]: messageId },
      }));
    }
    if (clientRef.current?.connected && selectedRoomIdRef.current === roomId) {
      sendRead(clientRef.current, roomId, messageId);
      return;
    }
    void chatApi.read(roomId, messageId).catch((error) => {
      delete lastReadSentRef.current[roomId];
      setActionError(formatError(error));
    });
  }

  function handleSocketPayload(payload: unknown, subscribedRoomId: string) {
    if (isChatEventView(payload)) {
      const event = payload;
      if (event.type === 'TYPING') {
        if (event.actorId && event.actorId !== currentUserId) {
          setTypingByRoom((prev) => ({
            ...prev,
            [event.roomId]: { ...(prev[event.roomId] ?? {}), [event.actorId as string]: Date.now() + 4_000 },
          }));
        }
        return;
      }
      if (event.type === 'MESSAGE_READ') {
        if (event.actorId && event.messageId) {
          setReadByRoom((prev) => ({
            ...prev,
            [event.roomId]: { ...(prev[event.roomId] ?? {}), [event.actorId as string]: event.messageId as string },
          }));
        }
        return;
      }
      if (event.message && isChatMessageView(event.message)) {
        confirmPending(event.message);
        upsertMessage({ ...event.message, localStatus: undefined });
        setRooms((prev) => {
          if (!prev.some((room) => room.id === event.roomId) && event.type === 'MENTION') {
            window.setTimeout(() => void reloadRoomsOnly(true), 0);
          }
          return prev;
        });
        updateRoomWithMessage(event.message, event.type);
        if (
          event.type === 'MESSAGE_CREATED'
          && event.roomId === subscribedRoomId
          && event.message.authorId !== currentUserId
        ) {
          markRead(event.roomId, event.message.id);
        }
      }
      return;
    }

    if (isChatMessageView(payload)) {
      confirmPending(payload);
      upsertMessage({ ...payload, localStatus: undefined });
      updateRoomWithMessage(payload, 'MESSAGE_CREATED');
      return;
    }

    if (isErrorPayload(payload) && payload.detail) {
      setActionError(localizeApiDetail(payload.detail, localeRef.current));
    }
  }

  function selectRoom(id: string, highlightMessageId: string | null = null) {
    setSelectedRoomId(id);
    setMobileConversation(true);
    setDetailsOpen(false);
    setMessageMenuId(null);
    setEditingId(null);
    setPendingFiles([]);
    setHighlightedMessageId(highlightMessageId);
    setRooms((prev) => prev.map((room) => (
      room.id === id ? { ...room, unreadCount: 0, mentionCount: 0 } : room
    )));
  }

  async function openDirect(userId: string) {
    if (creating) return;
    setCreating(true);
    setModalError(null);
    try {
      const room = await chatApi.direct(userId);
      upsertRoom(room);
      setNewChatOpen(false);
      selectRoom(room.id);
    } catch (error) {
      setModalError(formatError(error));
    } finally {
      setCreating(false);
    }
  }

  async function createGroup() {
    if (creating || groupMemberIds.length === 0) return;
    setCreating(true);
    setModalError(null);
    try {
      const room = await chatApi.createRoom({
        kind: 'GROUP',
        title: groupTitle.trim() || null,
        memberIds: groupMemberIds,
      });
      upsertRoom(room);
      setNewChatOpen(false);
      setGroupTitle('');
      setGroupMemberIds([]);
      selectRoom(room.id);
    } catch (error) {
      setModalError(formatError(error));
    } finally {
      setCreating(false);
    }
  }

  useEffect(() => {
    void loadRoomsAndDirectory();
    // Загрузка запускается при смене пользователя/права; остальные зависимости — стабильные API-вызовы.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canRead, currentUserId]);

  useEffect(() => {
    if (!canRead) return undefined;
    const timer = window.setInterval(() => {
      void reloadRoomsOnly(true);
      const people = directoryRef.current;
      if (people.length === 0) return;
      const ids = people.map((person) => person.id);
      const chunks: string[][] = [];
      for (let index = 0; index < ids.length; index += 500) chunks.push(ids.slice(index, index + 500));
      void Promise.all(chunks.map((chunk) => chatApi.presence(chunk)))
        .then((maps) => {
          const merged = Object.assign({}, ...maps) as Record<string, string>;
          setDirectory((prev) => prev.map((person) => ({
            ...person,
            presence: merged[person.id] ?? person.presence,
          })));
        })
        .catch(() => undefined);
    }, 30_000);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canRead]);

  useEffect(() => {
    if (selectedRoomId) void loadHistory(selectedRoomId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRoomId]);

  useEffect(() => {
    if (!canRead || !selectedRoomId) return undefined;
    let cancelled = false;
    chatApi.members(selectedRoomId)
      .then((items) => {
        if (cancelled) return;
        setMembersByRoom((prev) => ({ ...prev, [selectedRoomId]: items }));
        setReadByRoom((prev) => {
          const existing = prev[selectedRoomId] ?? {};
          const fromMembers: Record<string, string> = {};
          items.forEach((member) => {
            if (member.lastReadMessageId && !existing[member.userId]) {
              fromMembers[member.userId] = member.lastReadMessageId;
            }
          });
          return { ...prev, [selectedRoomId]: { ...fromMembers, ...existing } };
        });
      })
      .catch((error) => {
        if (!cancelled) setActionError(formatError(error));
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canRead, selectedRoomId]);

  useEffect(() => {
    if (!canRead || !selectedRoomId) return undefined;
    setSocketConnected(false);
    let client: Client | null = null;
    client = connectChat(
      () => getAccessToken(),
      selectedRoomId,
      (payload) => handleSocketPayload(payload, selectedRoomId),
      (connected) => {
        if (client && clientRef.current === client) setSocketConnected(connected);
      },
    );
    clientRef.current = client;
    return () => {
      if (client && clientRef.current === client) clientRef.current = null;
      setSocketConnected(false);
      void client?.deactivate();
    };
    // Переподключение нужно при смене комнаты; обработчики используют refs и функциональные обновления.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canRead, selectedRoomId, currentUserId]);

  useEffect(() => () => {
    fallbackTimersRef.current.forEach((timer) => window.clearTimeout(timer));
    fallbackTimersRef.current.clear();
    pendingRef.current.clear();
  }, []);

  const selectedRoom = rooms.find((room) => room.id === selectedRoomId) ?? null;
  const selectedMessages = useMemo(
    () => (messagesByRoom[selectedRoomId] ?? []).filter((message) => !message.threadRootId),
    [messagesByRoom, selectedRoomId],
  );
  const lastSelectedMessage = selectedMessages[selectedMessages.length - 1] ?? null;
  const lastSelectedMessageId = lastSelectedMessage?.id ?? '';
  const selectedMembers = membersByRoom[selectedRoomId] ?? [];
  const myMembership = selectedMembers.find((member) => member.userId === currentUserId) ?? null;
  const effectiveRole = myMembership?.role ?? selectedRoom?.myRole ?? null;
  const canModerate = canModerateGlobally || effectiveRole === 'OWNER' || effectiveRole === 'ADMIN';
  const mutedUntilMs = myMembership?.mutedUntil ? Date.parse(myMembership.mutedUntil) : Number.NaN;
  const isMuted = Number.isFinite(mutedUntilMs) && mutedUntilMs > Date.now();
  const canWrite = Boolean(
    selectedRoom
    && canRead
    && canWritePermission
    && effectiveRole !== 'READONLY'
    && !isMuted
    && (!selectedRoom.readOnly || canModerate),
  );
  const canSendFiles = canWrite && canSendFilesPermission;
  const draft = selectedRoomId ? draftsByRoom[selectedRoomId] ?? '' : '';
  const selectedHistory = historyByRoom[selectedRoomId];
  const typingUsers = useMemo(() => {
    const now = Date.now();
    return Object.entries(typingByRoom[selectedRoomId] ?? {})
      .filter(([actorId, expiresAt]) => actorId !== currentUserId && expiresAt > now)
      .map(([actorId]) => userName(actorId));
    // userName зависит от directoryById/currentUserId; они входят в typingByRoom пересчёт через рендер.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [typingByRoom, selectedRoomId, currentUserId, directoryById, user?.displayName, locale]);

  useEffect(() => {
    if (!selectedRoomId || !lastSelectedMessage || lastSelectedMessage.localStatus) return;
    markRead(selectedRoomId, lastSelectedMessage.id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRoomId, lastSelectedMessageId, socketConnected]);

  useEffect(() => {
    if (highlightedMessageId) return;
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [selectedRoomId, lastSelectedMessageId, highlightedMessageId]);

  useEffect(() => {
    if (!highlightedMessageId) return undefined;
    const timer = window.setTimeout(() => {
      document.getElementById(`chat-message-${highlightedMessageId}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 60);
    return () => window.clearTimeout(timer);
  }, [highlightedMessageId, selectedRoomId, selectedMessages.length]);

  useEffect(() => {
    const hasTyping = Object.values(typingByRoom).some((room) => Object.keys(room).length > 0);
    if (!hasTyping) return undefined;
    const timer = window.setInterval(() => {
      const now = Date.now();
      setTypingByRoom((prev) => {
        let changed = false;
        const next: Record<string, Record<string, number>> = {};
        Object.entries(prev).forEach(([roomId, actors]) => {
          const alive = Object.fromEntries(Object.entries(actors).filter(([, expiresAt]) => expiresAt > now));
          if (Object.keys(alive).length !== Object.keys(actors).length) changed = true;
          if (Object.keys(alive).length > 0) next[roomId] = alive;
          else if (Object.keys(actors).length > 0) changed = true;
        });
        return changed ? next : prev;
      });
    }, 1_000);
    return () => window.clearInterval(timer);
  }, [typingByRoom]);

  useEffect(() => {
    const query = search.trim();
    if (!canRead || query.length < 2) {
      setSearchResults([]);
      setSearchingMessages(false);
      setSearchError(null);
      return undefined;
    }
    let cancelled = false;
    setSearchingMessages(true);
    const timer = window.setTimeout(() => {
      chatApi.search(query, null, 20)
        .then((items) => {
          if (cancelled) return;
          setSearchResults(items);
          setSearchError(null);
        })
        .catch((error) => {
          if (!cancelled) setSearchError(formatError(error));
        })
        .finally(() => {
          if (!cancelled) setSearchingMessages(false);
        });
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canRead, search]);

  useEffect(() => {
    if (!newChatOpen) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setNewChatOpen(false);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [newChatOpen]);

  const query = search.trim().toLowerCase();
  const visibleRooms = rooms.filter((room) => {
    if (!query) return true;
    const memberNames = room.memberIds.map(userName).join(' ');
    return `${roomTitle(room)} ${roomSubtitle(room)} ${roomPreview(room)} ${memberNames}`.toLowerCase().includes(query);
  });
  const channels = visibleRooms.filter((room) => room.kind !== 'DIRECT');
  const directRooms = visibleRooms.filter((room) => room.kind === 'DIRECT');
  const peopleResults = query
    ? directory.filter((person) => (
      person.id !== currentUserId
      && `${person.displayName} ${person.username} ${person.email} ${person.department ?? ''}`.toLowerCase().includes(query)
    )).slice(0, 8)
    : [];
  const modalPeople = directory.filter((person) => {
    if (person.id === currentUserId) return false;
    const q = peopleSearch.trim().toLowerCase();
    return !q || `${person.displayName} ${person.username} ${person.email} ${person.department ?? ''}`.toLowerCase().includes(q);
  });

  const detailMembers: MemberView[] = selectedMembers.length > 0
    ? selectedMembers
    : (selectedRoom?.memberIds ?? []).map((userId) => ({
      userId,
      role: userId === currentUserId ? selectedRoom?.myRole ?? 'MEMBER' : 'MEMBER',
      mutedUntil: null,
      lastReadMessageId: readByRoom[selectedRoomId]?.[userId] ?? null,
      joinedAt: selectedRoom?.createdAt ?? new Date(0).toISOString(),
    }));
  const candidatesToAdd = directory.filter((person) => (
    person.id !== currentUserId && !detailMembers.some((member) => member.userId === person.id)
  ));
  const pinnedMessages = selectedMessages.filter((message) => message.pinned && !message.deleted);
  const latestPinned = pinnedMessages[pinnedMessages.length - 1] ?? null;
  const selectedMessageById = useMemo(
    () => new Map(selectedMessages.map((message) => [message.id, message])),
    [selectedMessages],
  );

  function writeBlockedReason(): string {
    if (!canWritePermission) return t('У вас нет права писать в чаты.');
    if (effectiveRole === 'READONLY') return t('Ваша роль в этой комнате позволяет только читать.');
    if (isMuted) {
      return t('Отправка временно ограничена до {date}.', {
        date: formatDateTime(locale, new Date(mutedUntilMs)),
      });
    }
    if (selectedRoom?.readOnly) return t('Эта комната доступна только для чтения.');
    return t('Отправка сообщений недоступна.');
  }

  function isReadByPeer(message: LocalMessage): boolean {
    const positions = readByRoom[selectedRoomId] ?? {};
    return detailMembers.some((member) => {
      if (member.userId === currentUserId) return false;
      const targetId = positions[member.userId] ?? member.lastReadMessageId;
      if (!targetId) return false;
      const target = selectedMessageById.get(targetId);
      return target ? compareMessages(target, message) >= 0 : false;
    });
  }

  function mentionedIdsFromText(text: string): string[] {
    if (!selectedRoom) return [];
    const memberIds = new Set(selectedRoom.memberIds);
    const byUsername = new Map(directory.map((person) => [person.username.toLowerCase(), person.id]));
    const result = new Set<string>();
    for (const match of text.matchAll(/@([A-Za-z0-9._-]+)/g)) {
      const id = byUsername.get(match[1].toLowerCase());
      if (id && id !== currentUserId && memberIds.has(id)) result.add(id);
    }
    return [...result];
  }

  function temporaryMessage(request: SendMessageRequest, localFileName?: string): LocalMessage {
    const clientMessageId = request.clientMessageId ?? newClientMessageId();
    return {
      id: `local-${clientMessageId}`,
      roomId: selectedRoomId,
      authorId: currentUserId,
      kind: request.kind ?? (request.fileRef ? 'FILE' : 'TEXT'),
      bodyMarkdown: request.bodyMarkdown ?? null,
      threadRootId: request.threadRootId ?? null,
      fileRef: request.fileRef ?? null,
      pinned: false,
      editedAt: null,
      deleted: false,
      clientMessageId,
      mentionedUserIds: request.mentionedUserIds ?? [],
      reactions: [],
      createdAt: new Date().toISOString(),
      localStatus: 'sending',
      localFileName,
    };
  }

  function retryMessage(message: LocalMessage) {
    if (!canWrite || message.localStatus !== 'failed') return;
    setMessagesByRoom((prev) => ({
      ...prev,
      [message.roomId]: (prev[message.roomId] ?? []).map((candidate) => (
        candidate.id === message.id ? { ...candidate, localStatus: 'sending' as const } : candidate
      )),
    }));
    dispatchSend(message.roomId, {
      bodyMarkdown: message.bodyMarkdown,
      threadRootId: message.threadRootId,
      clientMessageId: message.clientMessageId ?? newClientMessageId(),
      kind: message.kind,
      fileRef: message.fileRef,
      mentionedUserIds: message.mentionedUserIds,
    }, message.id);
  }

  async function send() {
    if (!selectedRoom || !canWrite || sending) return;
    const roomId = selectedRoom.id;
    const text = draft.trim();
    const files = [...pendingFiles];
    if (!text && files.length === 0) return;
    if (files.length > 0 && !canSendFiles) {
      setActionError(t('У вас нет права отправлять файлы в чат.'));
      return;
    }

    setSending(true);
    setActionError(null);
    setHighlightedMessageId(null);
    try {
      if (files.length === 0) {
        const request: SendMessageRequest = {
          bodyMarkdown: text,
          clientMessageId: newClientMessageId(),
          kind: 'TEXT',
          mentionedUserIds: mentionedIdsFromText(text),
        };
        const temp = temporaryMessage(request);
        upsertMessage(temp);
        updateDraft('');
        dispatchSend(roomId, request, temp.id);
      } else {
        const uploads = [];
        for (const file of files) uploads.push(await chatApi.uploadFile(roomId, file));
        const mentions = mentionedIdsFromText(text);
        uploads.forEach((upload, index) => {
          const kind: ChatMessageKind = upload.contentType.toLowerCase().startsWith('image/') ? 'IMAGE' : 'FILE';
          const request: SendMessageRequest = {
            bodyMarkdown: index === 0 ? text || null : null,
            clientMessageId: newClientMessageId(),
            kind,
            fileRef: upload.fileRef,
            mentionedUserIds: index === 0 ? mentions : [],
          };
          const temp = temporaryMessage(request, upload.fileName);
          upsertMessage(temp);
          dispatchSend(roomId, request, temp.id);
        });
        setPendingFiles([]);
        updateDraft('');
      }
    } catch (error) {
      setActionError(formatError(error));
    } finally {
      setSending(false);
      requestAnimationFrame(() => inputRef.current?.focus());
    }
  }

  function onDraftChange(value: string) {
    updateDraft(value);
    if (!canWrite || !value || !selectedRoomId) return;
    const now = Date.now();
    if (now - lastTypingSentRef.current >= 2_500) {
      lastTypingSentRef.current = now;
      sendTyping(clientRef.current, selectedRoomId);
    }
  }

  function insertEmoji(emoji: string) {
    const element = inputRef.current;
    const next = insertTextAtSelection(
      draft,
      emoji,
      element?.selectionStart ?? null,
      element?.selectionEnd ?? null,
    );
    updateDraft(next.value);
    requestAnimationFrame(() => {
      element?.focus();
      element?.setSelectionRange(next.cursor, next.cursor);
    });
  }

  function replyTo(message: LocalMessage) {
    const person = directoryById.get(message.authorId);
    const handle = message.authorId === currentUserId
      ? user?.username || firstGraphemes((user?.displayName ?? t('Вы')).split(' ')[0].toLowerCase(), 24)
      : person?.username || firstGraphemes(authorName(message).split(' ')[0].toLowerCase(), 24);
    const prefix = `@${handle} `;
    if (!draft.toLowerCase().startsWith(prefix.toLowerCase())) {
      updateDraft(draft.trim() ? `${prefix}${draft}` : prefix);
    }
    inputRef.current?.focus();
  }

  async function toggleReaction(message: LocalMessage, emoji = '👍') {
    if (message.deleted || message.localStatus) return;
    try {
      const saved = await chatApi.reaction(message.id, emoji);
      upsertMessage({ ...saved, localFileName: message.localFileName });
      updateRoomWithMessage(saved, 'MESSAGE_UPDATED');
    } catch (error) {
      setActionError(formatError(error));
    }
  }

  async function saveEdit(message: LocalMessage) {
    const bodyMarkdown = editDraft.trim();
    if (!bodyMarkdown) return;
    try {
      const saved = await chatApi.edit(message.id, bodyMarkdown);
      upsertMessage({ ...saved, localFileName: message.localFileName });
      updateRoomWithMessage(saved, 'MESSAGE_UPDATED');
      setEditingId(null);
      setMessageMenuId(null);
    } catch (error) {
      setActionError(formatError(error));
    }
  }

  async function deleteMessage(message: LocalMessage) {
    if (!window.confirm(t('Удалить это сообщение?'))) return;
    try {
      const saved = await chatApi.remove(message.id);
      upsertMessage({ ...saved, localStatus: undefined });
      updateRoomWithMessage(saved, 'MESSAGE_DELETED');
      setMessageMenuId(null);
    } catch (error) {
      setActionError(formatError(error));
    }
  }

  async function togglePin(message: LocalMessage) {
    try {
      const saved = await chatApi.pin(message.id, !message.pinned);
      upsertMessage({ ...saved, localFileName: message.localFileName });
      updateRoomWithMessage(saved, 'MESSAGE_UPDATED');
      setMessageMenuId(null);
    } catch (error) {
      setActionError(formatError(error));
    }
  }

  async function addMember() {
    if (!selectedRoom || !addMemberId) return;
    try {
      const room = await chatApi.addMembers(selectedRoom.id, [addMemberId]);
      upsertRoom(room);
      const items = await chatApi.members(selectedRoom.id);
      setMembersByRoom((prev) => ({ ...prev, [selectedRoom.id]: items }));
      setAddMemberId('');
    } catch (error) {
      setActionError(formatError(error));
    }
  }

  async function leaveRoom() {
    if (!selectedRoom || !window.confirm(t('Покинуть этот разговор?'))) return;
    const roomId = selectedRoom.id;
    try {
      await chatApi.leave(roomId);
      const remaining = rooms.filter((room) => room.id !== roomId);
      setRooms(remaining);
      setMessagesByRoom((prev) => {
        const next = { ...prev };
        delete next[roomId];
        return next;
      });
      setMembersByRoom((prev) => {
        const next = { ...prev };
        delete next[roomId];
        return next;
      });
      loadedRoomsRef.current.delete(roomId);
      setDetailsOpen(false);
      setSelectedRoomId(remaining[0]?.id ?? '');
      setMobileConversation(false);
    } catch (error) {
      setActionError(formatError(error));
    }
  }

  function openSearchResult(message: ChatMessageView) {
    upsertMessage(message);
    selectRoom(message.roomId, message.id);
  }

  const renderRoom = (room: RoomSummaryView) => {
    const title = roomTitle(room);
    const other = otherDirectUser(room);
    const selected = selectedRoomId === room.id;
    const roomMessages = messagesByRoom[room.id] ?? [];
    const loadedLast = roomMessages[roomMessages.length - 1];
    const activity = loadedLast?.createdAt ?? room.lastMessage?.createdAt ?? room.lastMessageAt ?? room.createdAt;
    return (
      <button
        key={room.id}
        type="button"
        onClick={() => selectRoom(room.id)}
        className={`flex w-full items-start gap-3 rounded-xl px-3 py-3 text-left transition ${selected ? 'bg-accent-soft/80' : 'hover:bg-black/[0.035]'}`}
      >
        <RealRoomMark room={room} title={title} online={other?.presence.toUpperCase() === 'ONLINE'} />
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-2">
            <span className={`min-w-0 flex-1 truncate text-sm ${room.unreadCount > 0 ? 'font-semibold text-ink' : 'font-medium text-ink-soft'}`}>{title}</span>
            {activity && <span className="shrink-0 text-[11px] text-faint">{roomTimeLabel(locale, activity, t)}</span>}
          </span>
          <span className="mt-0.5 block truncate text-[13px] text-muted">{roomPreview(room)}</span>
          <span className="mt-0.5 block truncate text-[11px] text-faint">{roomSubtitle(room)}</span>
        </span>
        {(room.unreadCount > 0 || room.mentionCount > 0) && (
          <span className="flex shrink-0 flex-col items-end gap-1">
            {room.unreadCount > 0 && (
              <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-accent px-1.5 text-[11px] font-semibold text-white">
                {room.unreadCount}
              </span>
            )}
            {room.mentionCount > 0 && (
              <span className="rounded-full border border-accent/25 bg-accent-soft px-1.5 py-0.5 text-[10px] font-semibold text-accent-dark">
                @{room.mentionCount}
              </span>
            )}
          </span>
        )}
      </button>
    );
  };

  if (!user) return <LoadingScreen label={t('Загружаем сообщения…')} />;

  if (!canRead) {
    return (
      <div className="flex h-full items-center justify-center p-4">
        <div className="w-full max-w-md rounded-[18px] border border-line bg-surface shadow-soft">
          <EmptyState
            icon={Lock}
            title={t('Нет доступа к чату')}
            description={t('Для вашей учётной записи не выдано право чтения чатов. Обратитесь к администратору.')}
          />
        </div>
      </div>
    );
  }

  const selectedTitle = selectedRoom ? roomTitle(selectedRoom) : '';
  const selectedOther = selectedRoom ? otherDirectUser(selectedRoom) : undefined;
  const headerSubtitle = !selectedRoom
    ? ''
    : typingUsers.length === 1
      ? t('{name} печатает…', { name: typingUsers[0] })
      : typingUsers.length > 1
        ? t('Печатают: {names}', { names: typingUsers.join(', ') })
        : `${roomSubtitle(selectedRoom)}${socketConnected ? '' : ` · ${t('Нет realtime-соединения')}`}`;

  return (
    <div className="h-full lg:p-3 xl:p-4">
      <div className="flex h-full min-h-0 overflow-hidden bg-surface lg:rounded-[18px] lg:border lg:border-line lg:shadow-soft">
        <aside className={`${mobileConversation ? 'hidden md:flex' : 'flex'} w-full shrink-0 flex-col border-r border-line bg-chrome md:w-[320px] xl:w-[344px]`}>
          <header className="shrink-0 px-4 pb-3 pt-5">
            <div className="flex items-center">
              <h1 className="flex-1 text-[19px] font-semibold tracking-[-0.025em]">{t('Сообщения')}</h1>
              <span
                className={`mr-1 h-2 w-2 rounded-full ${socketConnected ? 'bg-emerald-500' : 'bg-amber-400'}`}
                title={socketConnected ? t('Realtime-соединение установлено') : t('Нет realtime-соединения')}
                aria-label={socketConnected ? t('Realtime-соединение установлено') : t('Нет realtime-соединения')}
              />
              <IconButton
                icon={PenSquare}
                label={t('Новый разговор')}
                onClick={() => {
                  setModalError(null);
                  setNewChatOpen(true);
                }}
              />
            </div>
            <div className="relative mt-3">
              <Search size={16} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-faint" />
              <input
                ref={searchInputRef}
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder={t('Поиск людей и каналов')}
                aria-label={t('Поиск людей и каналов')}
                className="search-field bg-black/[0.04]"
              />
            </div>
          </header>

          <div className="scroll-area min-h-0 flex-1 px-2 pb-4">
            {roomsLoading && (
              <div className="flex items-center gap-2 px-3 py-8 text-sm text-muted">
                <Loader2 size={17} className="animate-spin" /> {t('Загружаем сообщения…')}
              </div>
            )}

            {loadError && (
              <div className="px-1 py-2">
                <Notice tone="error">
                  <p>{loadError}</p>
                  <button
                    type="button"
                    onClick={() => void loadRoomsAndDirectory()}
                    className="mt-2 font-semibold underline decoration-red-200 underline-offset-2"
                  >
                    {t('Повторить')}
                  </button>
                </Notice>
              </div>
            )}

            {!roomsLoading && channels.length > 0 && (
              <>
                <p className="section-label mb-1 mt-2">{t('Каналы и группы')}</p>
                <div className="space-y-0.5">{channels.map(renderRoom)}</div>
              </>
            )}

            {!roomsLoading && directRooms.length > 0 && (
              <>
                <p className="section-label mb-1 mt-5">{t('Личные сообщения')}</p>
                <div className="space-y-0.5">{directRooms.map(renderRoom)}</div>
              </>
            )}

            {!roomsLoading && peopleResults.length > 0 && (
              <>
                <p className="section-label mb-1 mt-5">{t('Люди')}</p>
                <div className="space-y-0.5">
                  {peopleResults.map((person) => (
                    <button
                      key={person.id}
                      type="button"
                      onClick={() => void openDirect(person.id)}
                      className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition hover:bg-black/[0.035]"
                    >
                      <Avatar name={person.displayName} size={38} presence={person.presence.toUpperCase() === 'ONLINE'} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium text-ink-soft">{person.displayName}</span>
                        <span className="block truncate text-xs text-faint">
                          {person.department || person.email} · {presenceLabel(person.presence)}
                        </span>
                      </span>
                    </button>
                  ))}
                </div>
              </>
            )}

            {!roomsLoading && query.length >= 2 && (
              <>
                <p className="section-label mb-1 mt-5">{t('Найденные сообщения')}</p>
                {searchingMessages && (
                  <div className="flex items-center gap-2 px-3 py-3 text-sm text-muted">
                    <Loader2 size={15} className="animate-spin" /> {t('Ищем…')}
                  </div>
                )}
                {searchError && <div className="px-1 py-1"><Notice tone="error">{searchError}</Notice></div>}
                {!searchingMessages && !searchError && searchResults.length === 0 && (
                  <p className="px-3 py-2 text-sm text-faint">{t('Сообщений по запросу не найдено.')}</p>
                )}
                <div className="space-y-0.5">
                  {searchResults.map((message) => {
                    const resultRoom = rooms.find((room) => room.id === message.roomId);
                    return (
                      <button
                        key={message.id}
                        type="button"
                        onClick={() => openSearchResult(message)}
                        className="w-full rounded-xl px-3 py-2.5 text-left transition hover:bg-black/[0.035]"
                      >
                        <span className="flex items-center gap-2 text-xs font-medium text-ink-soft">
                          <span className="min-w-0 flex-1 truncate">{resultRoom ? roomTitle(resultRoom) : t('Разговор')}</span>
                          <span className="shrink-0 font-normal text-faint">{formatDateTime(locale, message.createdAt)}</span>
                        </span>
                        <span className="mt-0.5 block truncate text-[13px] text-muted">
                          {authorName(message)}: {messagePreview(message, t)}
                        </span>
                      </button>
                    );
                  })}
                </div>
              </>
            )}

            {!roomsLoading && !loadError && visibleRooms.length === 0 && peopleResults.length === 0 && searchResults.length === 0 && (
              <EmptyState
                icon={Search}
                title={query ? t('Ничего не найдено') : t('Чатов пока нет')}
                description={query
                  ? t('Попробуйте другое имя или название канала.')
                  : t('Создайте первый личный или групповой разговор.')}
                action={!query ? (
                  <button type="button" onClick={() => setNewChatOpen(true)} className="btn-primary">
                    <PenSquare size={16} /> {t('Новый разговор')}
                  </button>
                ) : undefined}
              />
            )}
          </div>
        </aside>

        <section className={`${mobileConversation ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 flex-col bg-surface`}>
          {!selectedRoom ? (
            <EmptyState
              icon={Hash}
              title={roomsLoading ? t('Загружаем сообщения…') : t('Чатов пока нет')}
              description={roomsLoading ? undefined : t('Создайте первый личный или групповой разговор.')}
              action={!roomsLoading ? (
                <button type="button" onClick={() => setNewChatOpen(true)} className="btn-primary">
                  <PenSquare size={16} /> {t('Новый разговор')}
                </button>
              ) : undefined}
            />
          ) : (
            <>
              <header className="flex min-h-[68px] shrink-0 items-center gap-3 border-b border-line px-4 py-3">
                <button
                  type="button"
                  onClick={() => setMobileConversation(false)}
                  className="icon-btn md:hidden"
                  aria-label={t('К списку чатов')}
                >
                  <ArrowLeft size={19} />
                </button>
                <RealRoomMark
                  room={selectedRoom}
                  title={selectedTitle}
                  online={selectedOther?.presence.toUpperCase() === 'ONLINE'}
                  size={42}
                />
                <div className="min-w-0 flex-1">
                  <h2 className="truncate text-[16px] font-semibold tracking-[-0.015em]">{selectedTitle}</h2>
                  <p className="truncate text-xs text-muted" aria-live="polite">{headerSubtitle}</p>
                </div>
                <div className="flex items-center gap-0.5">
                  <span className="hidden sm:block">
                    <IconButton
                      icon={Search}
                      label={t('Поиск в разговоре')}
                      onClick={() => {
                        setMobileConversation(false);
                        window.setTimeout(() => searchInputRef.current?.focus(), 50);
                      }}
                    />
                  </span>
                  <IconButton
                    icon={Users}
                    label={t('Участники и сведения о разговоре')}
                    onClick={() => setDetailsOpen((value) => !value)}
                    aria-expanded={detailsOpen}
                  />
                  <IconButton
                    icon={MoreHorizontal}
                    label={t('Ещё действия')}
                    onClick={() => setDetailsOpen((value) => !value)}
                    aria-expanded={detailsOpen}
                  />
                </div>
              </header>

              {detailsOpen && (
                <div className="shrink-0 border-b border-line bg-chrome px-4 py-3">
                  <div className="mx-auto grid max-w-[980px] gap-4 md:grid-cols-[minmax(0,1fr)_280px]">
                    <div>
                      <p className="section-label mb-2 px-0">{t('Участники')}</p>
                      <div className="scroll-area max-h-48 space-y-1 pr-1">
                        {detailMembers.map((member) => {
                          const person = directoryById.get(member.userId);
                          return (
                            <div key={member.userId} className="flex items-center gap-2.5 rounded-lg px-2 py-1.5">
                              <Avatar
                                name={userName(member.userId)}
                                size={32}
                                presence={person?.presence.toUpperCase() === 'ONLINE'}
                              />
                              <span className="min-w-0 flex-1">
                                <span className="block truncate text-sm font-medium text-ink-soft">{userName(member.userId)}</span>
                                <span className="block truncate text-xs text-faint">
                                  {person?.department || person?.email || ''} · {presenceLabel(person?.presence)}
                                </span>
                              </span>
                              <span className="chip shrink-0">{roleLabel(member.role)}</span>
                            </div>
                          );
                        })}
                      </div>
                    </div>
                    <div className="space-y-3">
                      {selectedRoom.kind !== 'DIRECT' && canModerate && (
                        <div>
                          <label htmlFor="chat-add-member" className="field-label">{t('Добавить участника')}</label>
                          <div className="flex gap-2">
                            <select
                              id="chat-add-member"
                              value={addMemberId}
                              onChange={(event) => setAddMemberId(event.target.value)}
                              className="field field-sm min-w-0 flex-1"
                            >
                              <option value="">{t('Выберите сотрудника')}</option>
                              {candidatesToAdd.map((person) => (
                                <option key={person.id} value={person.id}>{person.displayName}</option>
                              ))}
                            </select>
                            <button
                              type="button"
                              onClick={() => void addMember()}
                              disabled={!addMemberId}
                              className="btn-secondary h-9 shrink-0 px-3"
                            >
                              <UserPlus size={15} /> {t('Добавить')}
                            </button>
                          </div>
                        </div>
                      )}
                      <button type="button" onClick={() => void leaveRoom()} className="btn-danger-ghost">
                        <LogOut size={15} /> {t('Покинуть разговор')}
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {latestPinned && (
                <button
                  type="button"
                  onClick={() => setHighlightedMessageId(latestPinned.id)}
                  className="flex shrink-0 items-center gap-2 border-b border-accent/10 bg-accent-soft/50 px-4 py-2 text-left text-xs text-accent-dark transition hover:bg-accent-soft"
                >
                  <span className="emoji-text" aria-hidden="true">📌</span>
                  <span className="font-semibold">{t('Закреплено')}</span>
                  <span className="min-w-0 flex-1 truncate">{messagePreview(latestPinned, t)}</span>
                  {pinnedMessages.length > 1 && <span className="shrink-0">{pinnedMessages.length}</span>}
                </button>
              )}

              {actionError && (
                <div className="shrink-0 px-3 pt-3 sm:px-5">
                  <div className="mx-auto max-w-[820px]">
                    <Notice tone="error">
                      <div className="flex items-start gap-2">
                        <span className="min-w-0 flex-1">{actionError}</span>
                        <button
                          type="button"
                          onClick={() => setActionError(null)}
                          className="icon-btn-sm -mr-1 -mt-1 h-7 w-7"
                          aria-label={t('Скрыть ошибку')}
                        >
                          <X size={14} />
                        </button>
                      </div>
                    </Notice>
                  </div>
                </div>
              )}

              <div ref={scrollRef} className="scroll-area min-h-0 flex-1 px-3 py-5 sm:px-5">
                <div className="mx-auto max-w-[820px]">
                  {selectedHistory?.hasMore && (
                    <div className="mb-5 text-center">
                      <button
                        type="button"
                        onClick={() => void loadOlder()}
                        disabled={olderLoadingRoomId === selectedRoomId}
                        className="btn-secondary h-9 px-3 text-[13px]"
                      >
                        {olderLoadingRoomId === selectedRoomId
                          ? <Loader2 size={15} className="animate-spin" />
                          : null}
                        {t('Показать более ранние сообщения')}
                      </button>
                    </div>
                  )}

                  {historyLoadingRoomId === selectedRoomId && selectedMessages.length === 0 && (
                    <div className="flex items-center justify-center gap-2 py-16 text-sm text-muted">
                      <Loader2 size={17} className="animate-spin" /> {t('Загружаем сообщения…')}
                    </div>
                  )}

                  {selectedMessages.map((message, index) => {
                    const previous = selectedMessages[index - 1];
                    const showDate = !previous || dateKey(previous.createdAt) !== dateKey(message.createdAt);
                    const gapMs = previous ? Date.parse(message.createdAt) - Date.parse(previous.createdAt) : Number.POSITIVE_INFINITY;
                    const sameAuthor = Boolean(
                      previous
                      && previous.authorId === message.authorId
                      && !showDate
                      && gapMs >= 0
                      && gapMs < 5 * 60 * 1000,
                    );
                    const mine = message.authorId === currentUserId;
                    const author = authorName(message);
                    const isEditing = editingId === message.id;
                    const menuOpen = messageMenuId === message.id;
                    const canEdit = mine && !message.deleted && !message.localStatus;
                    const canDeleteOrPin = (mine || canModerate) && !message.localStatus;
                    const read = mine && !message.localStatus && isReadByPeer(message);

                    return (
                      <Fragment key={message.id}>
                        {showDate && (
                          <div className="mb-5 mt-5 flex items-center gap-3 text-[11px] font-medium text-faint first:mt-0">
                            <span className="h-px flex-1 bg-line" />
                            {dateLabel(locale, message.createdAt, t)}
                            <span className="h-px flex-1 bg-line" />
                          </div>
                        )}
                        <div
                          id={`chat-message-${message.id}`}
                          className={`group relative flex gap-3 rounded-xl px-2 py-1.5 transition hover:bg-chrome ${sameAuthor ? '' : 'mt-4 first:mt-0'} ${highlightedMessageId === message.id ? 'bg-accent-soft/80 ring-2 ring-accent/25' : ''}`}
                        >
                          <div className="w-10 shrink-0">
                            {!sameAuthor && <Avatar name={author} size={38} />}
                          </div>
                          <div className="min-w-0 max-w-[720px] flex-1">
                            {!sameAuthor && (
                              <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                                <span className="text-sm font-semibold text-ink">{author}</span>
                                {mine && <span className="text-[11px] font-medium text-faint">{t('Вы')}</span>}
                                <time
                                  className="text-[11px] text-faint"
                                  dateTime={message.createdAt}
                                  title={formatDateTime(locale, message.createdAt)}
                                >
                                  {formatTime(locale, message.createdAt)}
                                </time>
                                {mine && !message.localStatus && (
                                  read
                                    ? <CheckCheck size={13} className="translate-y-0.5 text-accent" aria-label={t('Прочитано')} />
                                    : <Check size={13} className="translate-y-0.5 text-faint" aria-label={t('Отправлено')} />
                                )}
                                {message.pinned && !message.deleted && (
                                  <span className="text-[11px] font-medium text-accent-dark">📌 {t('Закреплено')}</span>
                                )}
                              </div>
                            )}

                            {isEditing ? (
                              <div className="mt-1">
                                <textarea
                                  value={editDraft}
                                  onChange={(event) => setEditDraft(event.target.value)}
                                  rows={3}
                                  maxLength={32_000}
                                  className="field"
                                  aria-label={t('Текст сообщения')}
                                  autoFocus
                                  onKeyDown={(event) => {
                                    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
                                      event.preventDefault();
                                      void saveEdit(message);
                                    }
                                    if (event.key === 'Escape') setEditingId(null);
                                  }}
                                />
                                <div className="mt-2 flex gap-2">
                                  <button type="button" onClick={() => void saveEdit(message)} disabled={!editDraft.trim()} className="btn-primary h-9 px-3 text-[13px]">
                                    {t('Сохранить')}
                                  </button>
                                  <button type="button" onClick={() => setEditingId(null)} className="btn-ghost h-9 px-3 text-[13px]">
                                    {t('Отмена')}
                                  </button>
                                </div>
                              </div>
                            ) : message.deleted ? (
                              <p className="mt-0.5 text-[14.5px] italic leading-relaxed text-faint">{t('Сообщение удалено')}</p>
                            ) : (
                              <>
                                {message.bodyMarkdown?.trim() && (
                                  <div
                                    className="message-markdown mt-0.5 text-[14.5px] leading-relaxed text-ink-soft [&_p]:m-0"
                                    dangerouslySetInnerHTML={renderChatMarkdown(message.bodyMarkdown)}
                                  />
                                )}
                                {message.fileRef && (
                                  <FileAttachment message={message} onError={setActionError} />
                                )}
                                {!message.bodyMarkdown?.trim() && !message.fileRef && (
                                  <p className="mt-0.5 text-[14.5px] leading-relaxed text-muted">{messagePreview(message, t)}</p>
                                )}
                              </>
                            )}

                            {(message.editedAt || message.localStatus) && !isEditing && (
                              <div className="mt-1 flex flex-wrap items-center gap-2 text-[11px] text-faint">
                                {message.editedAt && !message.deleted && <span>{t('изменено')}</span>}
                                {message.localStatus === 'sending' && (
                                  <span className="inline-flex items-center gap-1">
                                    <Loader2 size={12} className="animate-spin" /> {t('Отправляется…')}
                                  </span>
                                )}
                                {message.localStatus === 'failed' && (
                                  <>
                                    <span className="font-medium text-danger">{t('Не отправлено')}</span>
                                    <button
                                      type="button"
                                      onClick={() => retryMessage(message)}
                                      className="font-semibold text-accent-dark underline decoration-accent/30 underline-offset-2"
                                    >
                                      {t('Повторить')}
                                    </button>
                                  </>
                                )}
                              </div>
                            )}

                            {!message.deleted && message.reactions.length > 0 && (
                              <div className="mt-1.5 flex flex-wrap gap-1.5">
                                {message.reactions.map((reaction) => {
                                  const reacted = reaction.userIds.includes(currentUserId);
                                  return (
                                    <button
                                      key={reaction.emoji}
                                      type="button"
                                      onClick={() => void toggleReaction(message, reaction.emoji)}
                                      disabled={Boolean(message.localStatus)}
                                      className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-1 text-[11px] font-medium transition disabled:opacity-50 ${reacted ? 'border-accent/25 bg-accent-soft text-accent-dark' : 'border-line bg-surface text-muted hover:border-accent/25'}`}
                                      aria-label={`${t('Реакция')} ${reaction.emoji}: ${reaction.count}`}
                                    >
                                      <span className="emoji-text text-[13px] leading-none">{reaction.emoji}</span>
                                      {reaction.count}
                                    </button>
                                  );
                                })}
                              </div>
                            )}
                          </div>

                          {!message.deleted && !isEditing && (
                            <div className={`absolute -top-3 right-3 z-20 flex items-center rounded-lg border border-line bg-surface p-0.5 shadow-soft transition ${menuOpen ? 'opacity-100' : 'opacity-100 md:opacity-0 md:group-hover:opacity-100 md:group-focus-within:opacity-100'}`}>
                              <button
                                type="button"
                                onClick={() => void toggleReaction(message)}
                                disabled={Boolean(message.localStatus)}
                                className="icon-btn-sm emoji-text text-[15px] disabled:opacity-40"
                                title={t('Нравится')}
                                aria-label={t('Нравится')}
                              >
                                👍
                              </button>
                              <button
                                type="button"
                                onClick={() => replyTo(message)}
                                className="icon-btn-sm"
                                title={t('Ответить')}
                                aria-label={t('Ответить')}
                              >
                                <Reply size={15} />
                              </button>
                              {(canEdit || canDeleteOrPin) && (
                                <button
                                  type="button"
                                  onClick={() => setMessageMenuId(menuOpen ? null : message.id)}
                                  className="icon-btn-sm"
                                  title={t('Ещё')}
                                  aria-label={t('Ещё действия с сообщением')}
                                  aria-expanded={menuOpen}
                                >
                                  <MoreHorizontal size={15} />
                                </button>
                              )}
                            </div>
                          )}

                          {menuOpen && !isEditing && (
                            <div className="absolute right-3 top-9 z-30 w-52 rounded-xl border border-line bg-surface p-1.5 shadow-pop" role="menu">
                              {canDeleteOrPin && (
                                <button
                                  type="button"
                                  onClick={() => void togglePin(message)}
                                  className="nav-row"
                                  role="menuitem"
                                >
                                  {message.pinned ? <PinOff size={15} /> : <Pin size={15} />}
                                  {message.pinned ? t('Открепить') : t('Закрепить')}
                                </button>
                              )}
                              {canEdit && (
                                <button
                                  type="button"
                                  onClick={() => {
                                    setEditingId(message.id);
                                    setEditDraft(message.bodyMarkdown ?? '');
                                    setMessageMenuId(null);
                                  }}
                                  className="nav-row"
                                  role="menuitem"
                                >
                                  <PenSquare size={15} /> {t('Изменить')}
                                </button>
                              )}
                              {canDeleteOrPin && (
                                <button
                                  type="button"
                                  onClick={() => void deleteMessage(message)}
                                  className="nav-row text-danger hover:bg-red-50 hover:text-danger"
                                  role="menuitem"
                                >
                                  <Trash2 size={15} /> {t('Удалить')}
                                </button>
                              )}
                            </div>
                          )}
                        </div>
                      </Fragment>
                    );
                  })}

                  {historyLoadingRoomId !== selectedRoomId && selectedMessages.length === 0 && (
                    <EmptyState
                      icon={Hash}
                      title={t('Пока нет сообщений')}
                      description={t('Начните разговор первым сообщением ниже.')}
                    />
                  )}
                  <div ref={bottomRef} />
                </div>
              </div>

              <div className="shrink-0 px-3 pb-3 sm:px-5 sm:pb-5">
                <div className="mx-auto max-w-[820px]">
                  {!canWrite ? (
                    <div className="flex items-center justify-center gap-2 rounded-2xl border border-line bg-chrome px-4 py-3.5 text-center text-sm text-muted">
                      <Lock size={16} className="shrink-0" /> {writeBlockedReason()}
                    </div>
                  ) : (
                    <div className="rounded-[18px] border border-line bg-surface shadow-soft transition focus-within:border-accent/40 focus-within:ring-4 focus-within:ring-accent/10">
                      {pendingFiles.length > 0 && (
                        <div className="flex flex-wrap gap-2 px-4 pt-3">
                          {pendingFiles.map((file, index) => (
                            <span
                              key={`${file.name}-${file.size}-${file.lastModified}-${index}`}
                              className="chip max-w-full bg-chrome"
                            >
                              <Paperclip size={13} className="shrink-0" />
                              <span className="min-w-0 truncate">{file.name}</span>
                              <button
                                type="button"
                                onClick={() => setPendingFiles((prev) => prev.filter((_, candidateIndex) => candidateIndex !== index))}
                                className="ml-0.5 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full hover:bg-black/[0.06]"
                                aria-label={t('Убрать файл {name}', { name: file.name })}
                              >
                                <X size={12} />
                              </button>
                            </span>
                          ))}
                        </div>
                      )}
                      <textarea
                        ref={inputRef}
                        value={draft}
                        onChange={(event) => onDraftChange(event.target.value)}
                        rows={2}
                        maxLength={32_000}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' && !event.shiftKey) {
                            event.preventDefault();
                            void send();
                          }
                        }}
                        placeholder={t('Сообщение для {name}…', { name: selectedTitle })}
                        aria-label={t('Сообщение для {name}…', { name: selectedTitle })}
                        className="max-h-36 min-h-[58px] w-full resize-none border-0 bg-transparent px-4 pb-1 pt-3.5 text-[14.5px] leading-relaxed outline-none placeholder:text-faint focus:ring-0"
                      />
                      <input
                        ref={fileInputRef}
                        type="file"
                        multiple
                        className="hidden"
                        onChange={(event) => {
                          const files = Array.from(event.target.files ?? []);
                          if (files.length > 0) {
                            setPendingFiles((prev) => [...prev, ...files].slice(0, 10));
                          }
                          event.target.value = '';
                        }}
                      />
                      <div className="flex items-center gap-0.5 px-2 pb-2">
                        <IconButton
                          icon={Paperclip}
                          label={t('Прикрепить файл')}
                          className="h-8 w-8"
                          size={16}
                          disabled={!canSendFiles}
                          onClick={() => fileInputRef.current?.click()}
                        />
                        <EmojiPickerButton onPick={insertEmoji} className="h-8 w-8" />
                        <span className="ml-2 hidden text-[11px] text-faint lg:inline">
                          {t('Enter — отправить · Shift+Enter — новая строка')}
                        </span>
                        <button
                          type="button"
                          onClick={() => void send()}
                          disabled={sending || (!draft.trim() && pendingFiles.length === 0)}
                          className="ml-auto flex h-9 w-9 items-center justify-center rounded-full bg-ink text-white transition hover:bg-ink-soft disabled:cursor-not-allowed disabled:bg-black/[0.08] disabled:text-faint"
                          aria-label={t('Отправить сообщение')}
                        >
                          {sending ? <Loader2 size={16} className="animate-spin" /> : <SendHorizontal size={16} />}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </>
          )}
        </section>
      </div>

      {newChatOpen && (
        <div
          className="fixed inset-0 z-50 flex items-end justify-center bg-ink/40 p-3 backdrop-blur-[3px] sm:items-center"
          onClick={() => setNewChatOpen(false)}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-label={t('Новый разговор')}
            className="flex max-h-[88vh] w-full max-w-lg flex-col overflow-hidden rounded-[20px] bg-surface shadow-pop"
            onClick={(event) => event.stopPropagation()}
          >
            <header className="flex items-center gap-3 border-b border-line px-5 py-4">
              <h2 className="flex-1 text-[17px] font-semibold tracking-[-0.02em]">{t('Новый разговор')}</h2>
              <IconButton icon={X} label={t('Закрыть')} onClick={() => setNewChatOpen(false)} />
            </header>

            <div className="shrink-0 px-5 pt-4">
              <div className="inline-flex rounded-full border border-line bg-chrome p-0.5" role="group" aria-label={t('Тип разговора')}>
                <button
                  type="button"
                  onClick={() => setNewChatMode('DIRECT')}
                  aria-pressed={newChatMode === 'DIRECT'}
                  className={`h-8 rounded-full px-3 text-[13px] font-medium transition ${newChatMode === 'DIRECT' ? 'bg-surface text-ink shadow-soft' : 'text-muted hover:text-ink'}`}
                >
                  {t('Личный')}
                </button>
                <button
                  type="button"
                  onClick={() => setNewChatMode('GROUP')}
                  disabled={!canCreateGroup}
                  aria-pressed={newChatMode === 'GROUP'}
                  className={`h-8 rounded-full px-3 text-[13px] font-medium transition disabled:cursor-not-allowed disabled:opacity-45 ${newChatMode === 'GROUP' ? 'bg-surface text-ink shadow-soft' : 'text-muted hover:text-ink'}`}
                >
                  {t('Групповой')}
                </button>
              </div>

              {newChatMode === 'GROUP' && (
                <div className="mt-4">
                  <label htmlFor="chat-group-title" className="field-label">{t('Название группы')}</label>
                  <input
                    id="chat-group-title"
                    value={groupTitle}
                    onChange={(event) => setGroupTitle(event.target.value)}
                    maxLength={256}
                    placeholder={t('Необязательно')}
                    className="field"
                  />
                </div>
              )}

              <div className="relative mt-4">
                <Search size={16} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-faint" />
                <input
                  value={peopleSearch}
                  onChange={(event) => setPeopleSearch(event.target.value)}
                  placeholder={t('Поиск людей…')}
                  aria-label={t('Поиск людей…')}
                  className="search-field"
                  autoFocus
                />
              </div>
              {modalError && <div className="mt-3"><Notice tone="error">{modalError}</Notice></div>}
            </div>

            <div className="scroll-area min-h-0 flex-1 px-3 py-3">
              {modalPeople.length === 0 && (
                <EmptyState
                  icon={Search}
                  title={t('Никого не найдено')}
                  description={t('Попробуйте другое имя, логин или подразделение.')}
                />
              )}
              {modalPeople.map((person) => {
                const checked = groupMemberIds.includes(person.id);
                const content = (
                  <>
                    <Avatar
                      name={person.displayName}
                      size={40}
                      presence={person.presence.toUpperCase() === 'ONLINE'}
                    />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium text-ink-soft">{person.displayName}</span>
                      <span className="block truncate text-xs text-faint">
                        @{person.username}{person.department ? ` · ${person.department}` : ''} · {presenceLabel(person.presence)}
                      </span>
                    </span>
                    {newChatMode === 'GROUP' && (
                      <input
                        type="checkbox"
                        checked={checked}
                        readOnly
                        className="h-4 w-4 shrink-0 accent-[#3157D5]"
                        aria-label={person.displayName}
                      />
                    )}
                  </>
                );
                return newChatMode === 'DIRECT' ? (
                  <button
                    key={person.id}
                    type="button"
                    disabled={creating}
                    onClick={() => void openDirect(person.id)}
                    className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition hover:bg-black/[0.035] disabled:opacity-60"
                  >
                    {content}
                  </button>
                ) : (
                  <button
                    key={person.id}
                    type="button"
                    aria-pressed={checked}
                    onClick={() => setGroupMemberIds((prev) => (
                      checked ? prev.filter((id) => id !== person.id) : [...prev, person.id]
                    ))}
                    className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition hover:bg-black/[0.035] ${checked ? 'bg-accent-soft/70' : ''}`}
                  >
                    {content}
                  </button>
                );
              })}
            </div>

            {newChatMode === 'GROUP' && (
              <footer className="flex shrink-0 items-center gap-3 border-t border-line px-5 py-4">
                <span className="min-w-0 flex-1 text-sm text-muted">
                  {countLabel(locale, groupMemberIds.length, ['участник выбран', 'участника выбрано', 'участников выбрано'], ['member selected', 'members selected'])}
                </span>
                <button
                  type="button"
                  onClick={() => void createGroup()}
                  disabled={creating || groupMemberIds.length === 0}
                  className="btn-primary"
                >
                  {creating ? <Loader2 size={16} className="animate-spin" /> : <Users size={16} />}
                  {t('Создать группу')}
                </button>
              </footer>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

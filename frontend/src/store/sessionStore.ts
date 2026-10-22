import { create } from 'zustand';

const DEMO_SESSION_STORAGE_KEY = 'corvex.demo';
export const DEMO_READ_STATE_STORAGE_KEY = 'corvex.demo-read-state';

export interface DemoChatMessage {
  id: string;
  roomId: string;
  author: string;
  text: string;
  time: string;
  mine: true;
}

interface PersistedDemoReadState {
  userId: string | null;
  demoReadMailIds: string[];
  demoReadRoomIds: string[];
  demoSentChatMessages: DemoChatMessage[];
}

const MAX_DEMO_SENT_CHAT_MESSAGES = 100;

const EMPTY_DEMO_READ_STATE: PersistedDemoReadState = {
  userId: null,
  demoReadMailIds: [],
  demoReadRoomIds: [],
  demoSentChatMessages: [],
};

function demoSessionStorage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

function sanitizeIds(value: unknown): string[] {
  return Array.isArray(value)
    ? [...new Set(value.filter((item): item is string => typeof item === 'string'))]
    : [];
}

function sanitizeDemoString(value: unknown, maxLength: number): string | null {
  return typeof value === 'string' && value.length > 0 && value.length <= maxLength ? value : null;
}

function sanitizeDemoChatMessages(value: unknown): DemoChatMessage[] {
  if (!Array.isArray(value)) return [];
  const seenIds = new Set<string>();
  const messages: DemoChatMessage[] = [];

  for (const item of value) {
    if (!item || typeof item !== 'object') continue;
    const candidate = item as Partial<DemoChatMessage>;
    const id = sanitizeDemoString(candidate.id, 128);
    const roomId = sanitizeDemoString(candidate.roomId, 64);
    const author = sanitizeDemoString(candidate.author, 256);
    const text = sanitizeDemoString(candidate.text, 32_000);
    const time = sanitizeDemoString(candidate.time, 64);
    if (!id || !roomId || !author || !text || !time || candidate.mine !== true || seenIds.has(id)) {
      continue;
    }
    seenIds.add(id);
    messages.push({ id, roomId, author, text, time, mine: true });
  }

  return messages.slice(-MAX_DEMO_SENT_CHAT_MESSAGES);
}

function loadDemoReadState(): PersistedDemoReadState {
  try {
    const storage = demoSessionStorage();
    if (!storage || storage.getItem(DEMO_SESSION_STORAGE_KEY) !== '1') {
      return EMPTY_DEMO_READ_STATE;
    }
    const raw = storage.getItem(DEMO_READ_STATE_STORAGE_KEY);
    if (!raw) return EMPTY_DEMO_READ_STATE;
    const parsed = JSON.parse(raw) as Partial<PersistedDemoReadState>;
    return {
      userId: typeof parsed.userId === 'string' ? parsed.userId : null,
      demoReadMailIds: sanitizeIds(parsed.demoReadMailIds),
      demoReadRoomIds: sanitizeIds(parsed.demoReadRoomIds),
      demoSentChatMessages: sanitizeDemoChatMessages(parsed.demoSentChatMessages),
    };
  } catch {
    return EMPTY_DEMO_READ_STATE;
  }
}

const loadedDemoReadState = loadDemoReadState();
let persistedDemoReadUserId = loadedDemoReadState.userId;

function saveDemoReadState(
  userId: string,
  demoReadMailIds: string[],
  demoReadRoomIds: string[],
  demoSentChatMessages: DemoChatMessage[],
): void {
  persistedDemoReadUserId = userId;
  try {
    const storage = demoSessionStorage();
    if (!storage || storage.getItem(DEMO_SESSION_STORAGE_KEY) !== '1') return;
    storage.setItem(DEMO_READ_STATE_STORAGE_KEY, JSON.stringify({
      userId,
      demoReadMailIds,
      demoReadRoomIds,
      demoSentChatMessages: sanitizeDemoChatMessages(demoSentChatMessages),
    }));
  } catch {
    // Демо-режим должен продолжать работать и при недоступном sessionStorage.
  }
}

function clearDemoReadState(): void {
  persistedDemoReadUserId = null;
  try {
    demoSessionStorage()?.removeItem(DEMO_READ_STATE_STORAGE_KEY);
  } catch {
    // См. комментарий в saveDemoReadState.
  }
}

/** Сессия и эффективные права: UI прячет недоступные действия, но финальная проверка всегда на сервере. */
export interface SessionUser {
  id: string;
  username?: string;
  displayName: string;
  email: string;
  groups: string[];
  permissions: string[];
  source?: string;
  status?: string;
  department?: string | null;
  quotaBytes?: number;
  usedBytes?: number;
  mustChangePassword?: boolean;
  totpEnabled?: boolean;
}

interface SessionState {
  user: SessionUser | null;
  demoMode: boolean;
  /** Завершена ли первичная попытка восстановить сессию по refresh-cookie. */
  bootstrapped: boolean;
  /** ID писем, прочитанных в текущей демо-сессии; общий источник для MailPage и бейджа AppShell. */
  demoReadMailIds: string[];
  /** ID комнат, прочитанных в текущей демо-сессии; общий источник для DemoChatPage и бейджа AppShell. */
  demoReadRoomIds: string[];
  /** Сообщения, отправленные пользователем в демо-чате текущей вкладки. */
  demoSentChatMessages: DemoChatMessage[];
  setSession: (user: SessionUser, demoMode: boolean) => void;
  updateUser: (patch: Partial<SessionUser>) => void;
  clearSession: () => void;
  setBootstrapped: () => void;
  markDemoMailRead: (id: string) => void;
  markDemoRoomRead: (id: string) => void;
  addDemoSentChatMessage: (message: DemoChatMessage) => void;
  has: (permission: string) => boolean;
}

export const useSession = create<SessionState>((set, get) => ({
  user: null,
  demoMode: false,
  bootstrapped: false,
  demoReadMailIds: loadedDemoReadState.demoReadMailIds,
  demoReadRoomIds: loadedDemoReadState.demoReadRoomIds,
  demoSentChatMessages: loadedDemoReadState.demoSentChatMessages,
  setSession: (user, demoMode) => {
    const current = get();
    const sameSession = current.demoMode === demoMode && current.user?.id === user.id;
    const restoringDemo = demoMode
      && current.user === null
      && persistedDemoReadUserId === user.id;
    const keepDemoReadState = demoMode && (sameSession || restoringDemo);
    const demoReadMailIds = keepDemoReadState ? current.demoReadMailIds : [];
    const demoReadRoomIds = keepDemoReadState ? current.demoReadRoomIds : [];
    const demoSentChatMessages = keepDemoReadState ? current.demoSentChatMessages : [];

    set({
      user,
      demoMode,
      bootstrapped: true,
      demoReadMailIds,
      demoReadRoomIds,
      demoSentChatMessages,
    });

    if (demoMode) {
      saveDemoReadState(user.id, demoReadMailIds, demoReadRoomIds, demoSentChatMessages);
    } else {
      clearDemoReadState();
    }
  },
  updateUser: (patch) => set((s) => (s.user ? { user: { ...s.user, ...patch } } : s)),
  clearSession: () => {
    set({
      user: null,
      demoMode: false,
      demoReadMailIds: [],
      demoReadRoomIds: [],
      demoSentChatMessages: [],
    });
    clearDemoReadState();
  },
  setBootstrapped: () => set({ bootstrapped: true }),
  markDemoMailRead: (id) => {
    const current = get();
    if (current.demoReadMailIds.includes(id)) return;
    const demoReadMailIds = [...current.demoReadMailIds, id];
    set({ demoReadMailIds });
    if (current.demoMode && current.user) {
      saveDemoReadState(
        current.user.id,
        demoReadMailIds,
        current.demoReadRoomIds,
        current.demoSentChatMessages,
      );
    }
  },
  markDemoRoomRead: (id) => {
    const current = get();
    if (current.demoReadRoomIds.includes(id)) return;
    const demoReadRoomIds = [...current.demoReadRoomIds, id];
    set({ demoReadRoomIds });
    if (current.demoMode && current.user) {
      saveDemoReadState(
        current.user.id,
        current.demoReadMailIds,
        demoReadRoomIds,
        current.demoSentChatMessages,
      );
    }
  },
  addDemoSentChatMessage: (message) => {
    const current = get();
    if (!current.demoMode || !current.user) return;
    if (current.demoSentChatMessages.some((candidate) => candidate.id === message.id)) return;
    const demoSentChatMessages = [...current.demoSentChatMessages, message]
      .slice(-MAX_DEMO_SENT_CHAT_MESSAGES);
    set({ demoSentChatMessages });
    saveDemoReadState(
      current.user.id,
      current.demoReadMailIds,
      current.demoReadRoomIds,
      demoSentChatMessages,
    );
  },
  has: (permission) => get().user?.permissions.includes(permission) ?? false,
}));

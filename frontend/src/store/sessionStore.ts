import { create } from 'zustand';

const DEMO_SESSION_STORAGE_KEY = 'corvex.demo';
export const DEMO_READ_STATE_STORAGE_KEY = 'corvex.demo-read-state';

interface PersistedDemoReadState {
  userId: string | null;
  demoReadMailIds: string[];
  demoReadRoomIds: string[];
}

const EMPTY_DEMO_READ_STATE: PersistedDemoReadState = {
  userId: null,
  demoReadMailIds: [],
  demoReadRoomIds: [],
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
    };
  } catch {
    return EMPTY_DEMO_READ_STATE;
  }
}

const loadedDemoReadState = loadDemoReadState();
let persistedDemoReadUserId = loadedDemoReadState.userId;

function saveDemoReadState(userId: string, demoReadMailIds: string[], demoReadRoomIds: string[]): void {
  persistedDemoReadUserId = userId;
  try {
    const storage = demoSessionStorage();
    if (!storage || storage.getItem(DEMO_SESSION_STORAGE_KEY) !== '1') return;
    storage.setItem(DEMO_READ_STATE_STORAGE_KEY, JSON.stringify({
      userId,
      demoReadMailIds,
      demoReadRoomIds,
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
  setSession: (user: SessionUser, demoMode: boolean) => void;
  updateUser: (patch: Partial<SessionUser>) => void;
  clearSession: () => void;
  setBootstrapped: () => void;
  markDemoMailRead: (id: string) => void;
  markDemoRoomRead: (id: string) => void;
  has: (permission: string) => boolean;
}

export const useSession = create<SessionState>((set, get) => ({
  user: null,
  demoMode: false,
  bootstrapped: false,
  demoReadMailIds: loadedDemoReadState.demoReadMailIds,
  demoReadRoomIds: loadedDemoReadState.demoReadRoomIds,
  setSession: (user, demoMode) => {
    const current = get();
    const sameSession = current.demoMode === demoMode && current.user?.id === user.id;
    const restoringDemo = demoMode
      && current.user === null
      && persistedDemoReadUserId === user.id;
    const keepDemoReadState = demoMode && (sameSession || restoringDemo);
    const demoReadMailIds = keepDemoReadState ? current.demoReadMailIds : [];
    const demoReadRoomIds = keepDemoReadState ? current.demoReadRoomIds : [];

    set({
      user,
      demoMode,
      bootstrapped: true,
      demoReadMailIds,
      demoReadRoomIds,
    });

    if (demoMode) {
      saveDemoReadState(user.id, demoReadMailIds, demoReadRoomIds);
    } else {
      clearDemoReadState();
    }
  },
  updateUser: (patch) => set((s) => (s.user ? { user: { ...s.user, ...patch } } : s)),
  clearSession: () => {
    set({ user: null, demoMode: false, demoReadMailIds: [], demoReadRoomIds: [] });
    clearDemoReadState();
  },
  setBootstrapped: () => set({ bootstrapped: true }),
  markDemoMailRead: (id) => {
    const current = get();
    if (current.demoReadMailIds.includes(id)) return;
    const demoReadMailIds = [...current.demoReadMailIds, id];
    set({ demoReadMailIds });
    if (current.demoMode && current.user) {
      saveDemoReadState(current.user.id, demoReadMailIds, current.demoReadRoomIds);
    }
  },
  markDemoRoomRead: (id) => {
    const current = get();
    if (current.demoReadRoomIds.includes(id)) return;
    const demoReadRoomIds = [...current.demoReadRoomIds, id];
    set({ demoReadRoomIds });
    if (current.demoMode && current.user) {
      saveDemoReadState(current.user.id, current.demoReadMailIds, demoReadRoomIds);
    }
  },
  has: (permission) => get().user?.permissions.includes(permission) ?? false,
}));

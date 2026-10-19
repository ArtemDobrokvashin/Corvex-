import { create } from 'zustand';

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
  setSession: (user: SessionUser, demoMode: boolean) => void;
  updateUser: (patch: Partial<SessionUser>) => void;
  clearSession: () => void;
  setBootstrapped: () => void;
  has: (permission: string) => boolean;
}

export const useSession = create<SessionState>((set, get) => ({
  user: null,
  demoMode: false,
  bootstrapped: false,
  setSession: (user, demoMode) => set({ user, demoMode, bootstrapped: true }),
  updateUser: (patch) => set((s) => (s.user ? { user: { ...s.user, ...patch } } : s)),
  clearSession: () => set({ user: null, demoMode: false }),
  setBootstrapped: () => set({ bootstrapped: true }),
  has: (permission) => get().user?.permissions.includes(permission) ?? false,
}));

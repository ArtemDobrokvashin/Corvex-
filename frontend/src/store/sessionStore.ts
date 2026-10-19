import { create } from 'zustand';

/** Сессия и эффективные права: UI прячет недоступные действия, но финальная проверка всегда на сервере. */
export interface SessionUser {
  id: string;
  displayName: string;
  email: string;
  groups: string[];
  permissions: string[];
}

interface SessionState {
  user: SessionUser | null;
  demoMode: boolean;
  setSession: (user: SessionUser, demoMode: boolean) => void;
  has: (permission: string) => boolean;
}

export const useSession = create<SessionState>((set, get) => ({
  user: null,
  demoMode: false,
  setSession: (user, demoMode) => set({ user, demoMode }),
  has: (permission) => get().user?.permissions.includes(permission) ?? false,
}));

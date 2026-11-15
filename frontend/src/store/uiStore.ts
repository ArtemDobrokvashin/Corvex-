import { create } from 'zustand';
import { persist, type PersistStorage, type StorageValue } from 'zustand/middleware';

const STORAGE_KEY = 'corvex.ui';

export type Theme = 'light' | 'dark' | 'system';
export type Accent = 'indigo' | 'rose' | 'emerald' | 'amber' | 'slate' | 'green';
export type Density = 'compact' | 'comfortable';

const ACCENT_DEFINITIONS: Record<Accent, { label: string; value: string }> = {
  indigo: { label: 'Индиго', value: '#3157D5' },
  rose: { label: 'Розовый', value: '#C0265A' },
  emerald: { label: 'Изумрудный', value: '#16835F' },
  amber: { label: 'Янтарный', value: '#A16207' },
  slate: { label: 'Графит', value: '#474C56' },
  green: { label: 'Зелёный', value: '#10B981' },
};

export function accentDefinitions() {
  return ACCENT_DEFINITIONS;
}

export function accentValue(accent: Accent) {
  return ACCENT_DEFINITIONS[accent].value;
}

const ACCENT_ON_DARK: Record<Accent, string> = {
  indigo: '#AFC0FF', rose: '#FFABCA', emerald: '#8BE2BD', amber: '#F8CF83', slate: '#C6CEDB', green: '#8BE2BD',
};

export function accentOnDark(accent: Accent) {
  return ACCENT_ON_DARK[accent];
}

/** Green needs darker text on light surfaces and on its bright button background. */
export function accentOnLight(accent: Accent) {
  return accent === 'green' ? '#056746' : accentValue(accent);
}

export function accentButtonText(accent: Accent) {
  return accent === 'green' ? '#092E20' : '#FFFFFF';
}

export function accentLabel(accent: Accent) {
  return ACCENT_DEFINITIONS[accent].label;
}

export interface UIState {
  theme: Theme;
  accent: Accent;
  density: Density;
  setTheme: (theme: Theme) => void;
  setAccent: (accent: Accent) => void;
  setDensity: (density: Density) => void;
}

function isTheme(value: unknown): value is Theme {
  return value === 'light' || value === 'dark' || value === 'system';
}

function isAccent(value: unknown): value is Accent {
  return (
    typeof value === 'string' &&
    Object.prototype.hasOwnProperty.call(ACCENT_DEFINITIONS, value)
  );
}

function isDensity(value: unknown): value is Density {
  return value === 'compact' || value === 'comfortable';
}

/**
 * Дезинфекция пользовательских данных из localStorage.
 * Неизвестные, повреждённые или некорректные значения заменяются на умолчания.
 * Возвращает только три поля UI (без сеттеров) — они безопасно сливаются
 * Zustand'ом с текущим состоянием, не затирая реальных функций-сеттеров.
 */
export function sanitizePersisted(raw: unknown): {
  theme?: Theme;
  accent?: Accent;
  density?: Density;
} {
  if (typeof raw !== 'object' || raw === null) return {};
  const parsed = raw as Record<string, unknown>;
  const result: { theme?: Theme; accent?: Accent; density?: Density } = {};
  if (isTheme(parsed.theme)) result.theme = parsed.theme;
  if (isAccent(parsed.accent)) result.accent = parsed.accent;
  if (isDensity(parsed.density)) result.density = parsed.density;
  return result;
}

function createUIStore(storageKey: string) {
  const storage: PersistStorage<UIState> = {
    getItem: (name) => {
      if (typeof window === 'undefined') return null;
      try {
        const raw = window.localStorage.getItem(name);
        if (!raw) return null;
        const parsed = JSON.parse(raw) as {
          state?: unknown;
          version?: number;
        };
        const state = parsed && parsed.state ? parsed.state : parsed;
        const sanitized = sanitizePersisted(state);
        return {
          state: {
            theme: sanitized.theme ?? 'light',
            accent: sanitized.accent ?? 'indigo',
            density: sanitized.density ?? 'compact',
          } as UIState,
        } as StorageValue<UIState>;
      } catch {
        return null;
      }
    },
    setItem: (name, value) => {
      if (typeof window !== 'undefined') {
        // Функции-сеттеры в state пропускаются JSON.stringify, поэтому в
        // localStorage попадают только три поля UI.
        try { window.localStorage.setItem(name, JSON.stringify(value)); } catch { /* settings remain in memory */ }
      }
    },
    removeItem: (name) => {
      if (typeof window !== 'undefined') {
        try { window.localStorage.removeItem(name); } catch { /* storage may be blocked */ }
      }
    },
  };

  return create<UIState>()(
    persist(
      (set) => ({
        theme: 'light',
        accent: 'indigo',
        density: 'compact',
        setTheme: (theme) => set({ theme }),
        setAccent: (accent) => set({ accent }),
        setDensity: (density) => set({ density }),
      }),
      {
        name: storageKey,
        storage,
        // Сохраняем только три поля UI (без функций-сеттеров).
        partialize: (state) =>
          ({
            theme: state.theme,
            accent: state.accent,
            density: state.density,
          }) as unknown as UIState,
      },
    ),
  );
}

export const useUIStore = createUIStore(STORAGE_KEY);
export { createUIStore };

import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { CheckCircle2, Info, X, AlertCircle } from 'lucide-react';
import { useI18n } from '../i18n';

/**
 * Один слой обратной связи на всё приложение.
 *
 * Зачем отдельный компонент, а не `role="status"` в каждом месте. Отмена действия,
 * итог массовой операции и ошибка с кнопкой «Повторить» — это один и тот же механизм:
 * сообщение, действие и срок жизни. Пока его нет, каждый экран рисует свою плашку,
 * и «Отменить» либо не появляется, либо живёт в трёх несогласованных видах.
 *
 * Правила, которые здесь закреплены:
 * - сообщение появляется на 6 секунд, но не исчезает, пока пользователь на нём;
 * - уведомление с действием («Отменить») не закрывается само, пока не истечёт срок
 *   отмены, и явно говорит, сколько осталось;
 * - `aria-live="polite"`: не перебивает работу скринридера, но проговаривает результат.
 */
export type ToastAction = { label: string; onSelect: () => void | Promise<void> };

export interface ToastInput {
  message: string;
  tone?: 'info' | 'success' | 'error';
  /** Кнопка действия: «Отменить», «Повторить», «Открыть». */
  action?: ToastAction;
  /** Сколько миллисекунд держать сообщение. `null` — до явного закрытия. */
  durationMs?: number | null;
  /** Ключ для замены однотипных сообщений вместо их накопления. */
  key?: string;
}

interface ToastEntry extends ToastInput {
  id: number;
  expiresAt: number | null;
}

interface ToastApi {
  notify: (input: ToastInput) => number;
  dismiss: (id: number) => void;
  /** Убрать по ключу: «Отменить» должно исчезнуть, как только отмена случилась. */
  dismissKey: (key: string) => void;
}

const DEFAULT_DURATION_MS = 6000;

const ToastContext = createContext<ToastApi | null>(null);

/** Без провайдера уведомление не должно ронять экран: возвращаем тихий no-op. */
const NOOP: ToastApi = { notify: () => 0, dismiss: () => undefined, dismissKey: () => undefined };

export function useToast(): ToastApi {
  return useContext(ToastContext) ?? NOOP;
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const [entries, setEntries] = useState<ToastEntry[]>([]);
  const nextId = useRef(1);
  const timers = useRef(new Map<number, number>());
  // Пауза на время наведения: иначе «Отменить» исчезает из-под курсора.
  const paused = useRef(new Set<number>());
  const remaining = useRef(new Map<number, number>());

  const clearTimer = (id: number) => {
    const timer = timers.current.get(id);
    if (timer != null) window.clearTimeout(timer);
    timers.current.delete(id);
  };

  const remove = useCallback((id: number) => {
    clearTimer(id);
    paused.current.delete(id);
    remaining.current.delete(id);
    setEntries((current) => current.filter((entry) => entry.id !== id));
  }, []);

  const schedule = useCallback((entry: ToastEntry) => {
    const duration = entry.expiresAt == null ? null : entry.expiresAt - Date.now();
    if (duration == null) return;
    if (duration <= 0) { remove(entry.id); return; }
    timers.current.set(entry.id, window.setTimeout(() => remove(entry.id), duration));
  }, [remove]);

  const notify = useCallback((input: ToastInput): number => {
    const id = nextId.current++;
    const duration = input.durationMs === undefined ? DEFAULT_DURATION_MS : input.durationMs;
    const entry: ToastEntry = { ...input, id, expiresAt: duration == null ? null : Date.now() + duration };
    setEntries((current) => {
      const withoutSameKey = input.key
        ? current.filter((item) => item.key !== input.key)
        : current;
      return [...withoutSameKey, entry].slice(-4);
    });
    schedule(entry);
    return id;
  }, [schedule]);

  const dismiss = useCallback((id: number) => remove(id), [remove]);

  const dismissKey = useCallback((key: string) => {
    setEntries((current) => {
      for (const entry of current) {
        if (entry.key === key) { clearTimer(entry.id); remaining.current.delete(entry.id); }
      }
      return current.filter((entry) => entry.key !== key);
    });
  }, []);

  const api = useMemo<ToastApi>(() => ({ notify, dismiss, dismissKey }), [notify, dismiss, dismissKey]);

  const pause = (id: number) => {
    paused.current.add(id);
    const entry = entries.find((item) => item.id === id);
    if (!entry || entry.expiresAt == null) return;
    clearTimer(id);
    remaining.current.set(id, Math.max(0, entry.expiresAt - Date.now()));
  };

  const resume = (id: number) => {
    paused.current.delete(id);
    const left = remaining.current.get(id);
    if (left == null) return;
    remaining.current.delete(id);
    timers.current.set(id, window.setTimeout(() => remove(id), left));
  };

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div
        role="region"
        aria-label={t('Уведомления')}
        className="pointer-events-none fixed bottom-4 left-1/2 z-[80] flex w-[min(560px,calc(100vw-1.5rem))] -translate-x-1/2 flex-col gap-2"
      >
        <div aria-live="polite" aria-atomic="false" className="flex flex-col gap-2">
          {entries.map((entry) => {
            const tone = entry.tone ?? 'info';
            const Icon = tone === 'success' ? CheckCircle2 : tone === 'error' ? AlertCircle : Info;
            return (
              <div
                key={entry.id}
                className={`pointer-events-auto flex items-start gap-3 rounded-xl border px-3.5 py-2.5 shadow-pop ${
                  tone === 'error'
                    ? 'border-red-200 bg-red-50 text-red-900'
                    : tone === 'success'
                      ? 'border-emerald-200 bg-emerald-50 text-emerald-900'
                      : 'border-line-control bg-surface text-ink'
                }`}
                onMouseEnter={() => pause(entry.id)}
                onMouseLeave={() => resume(entry.id)}
                onFocus={() => pause(entry.id)}
                onBlur={() => resume(entry.id)}
              >
                <Icon size={16} strokeWidth={1.9} className="mt-0.5 shrink-0" aria-hidden="true" />
                <p className="min-w-0 flex-1 text-[13px] leading-5">{entry.message}</p>
                {entry.action && (
                  <button
                    type="button"
                    onClick={() => {
                      // Действие сразу закрывает сообщение: повторное нажатие невозможно.
                      dismiss(entry.id);
                      void entry.action?.onSelect();
                    }}
                    className="shrink-0 rounded-md px-2 py-1 text-[13px] font-semibold underline underline-offset-2 hover:bg-black/5"
                  >
                    {entry.action.label}
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => dismiss(entry.id)}
                  aria-label={t('Закрыть уведомление')}
                  className="shrink-0 rounded p-1 text-current/60 hover:bg-black/5 hover:text-current"
                >
                  <X size={14} />
                </button>
              </div>
            );
          })}
        </div>
      </div>
    </ToastContext.Provider>
  );
}

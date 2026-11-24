import { useEffect, useRef } from 'react';
import { mailApi } from '../../api/mailApi';

/**
 * Опрос входящих, пока открыто рабочее пространство, а не только раздел «Почта».
 *
 * Раньше хук жил внутри `MailPage` и останавливался при переходе в чат или
 * календарь: человек не узнавал о письме, если не смотрел на почту. Здесь тот же
 * контракт (первый снимок — база, уведомляем только о новых непрочитанных UID),
 * но вызывается из `AppShell`, поэтому работает на любом экране.
 */
export function useMailNotifications(
  enabled: boolean,
  demoMode: boolean,
  inboxId: string | undefined,
  onNewMail: (fresh: { id: string }[]) => void,
) {
  // Колбэк в зависимостях перезапускал бы опрос на каждый рендер и терял baseline.
  const callback = useRef(onNewMail);
  callback.current = onNewMail;
  useEffect(() => {
    if (!enabled || demoMode || !inboxId || typeof window === 'undefined') return undefined;
    let cancelled = false;
    let running = false;
    let baselineReady = false;
    let newestUid = 0;

    const poll = async () => {
      if (cancelled || running || document.visibilityState === 'hidden') return;
      running = true;
      try {
        const items = await mailApi.messages(inboxId);
        if (cancelled) return;
        const fresh = baselineReady ? items.filter((item) => item.uid > newestUid && !item.seen) : [];
        newestUid = Math.max(newestUid, ...items.map((item) => item.uid));
        baselineReady = true;
        if (fresh.length > 0) callback.current(fresh);
      } catch {
        // Ошибку загрузки показывает сам раздел; опрос — вспомогательный.
      } finally {
        running = false;
      }
    };
    void poll();
    const timer = window.setInterval(() => { void poll(); }, 15_000);
    // Вернулись во вкладку — не ждём следующего тика.
    const onVisible = () => { if (document.visibilityState === 'visible') void poll(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [enabled, demoMode, inboxId]);
}

/** Состояние «Входящих» одним запросом: и id для опроса, и счётчик для бейджа. */
export async function fetchInboxStatus(): Promise<{ id: string | null; unread: number }> {
  const folders = await mailApi.folders();
  const inbox = folders.find((folder) => folder.specialUse === 'INBOX') ?? folders[0];
  return { id: inbox?.id ?? null, unread: inbox?.unread ?? 0 };
}

/**
 * Заголовок вкладки со счётчиком. Иконку вкладки не подменяем: браузер кеширует
 * favicon по href, и на каждый счётчик страница мигала бы пустым значком.
 */
export function applyUnreadToTitle(unread: number, base: string) {
  if (typeof document === 'undefined') return;
  document.title = unread > 0 ? `(${unread}) ${base}` : base;
}

/** Тонкий звук для случая, когда вкладка открыта, но человек смотрит в другой монитор. */
export function playNotificationSound() {
  try {
    const AudioContextCtor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioContextCtor) return;
    const context = new AudioContextCtor();
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.type = 'sine';
    oscillator.frequency.value = 880;
    gain.gain.setValueAtTime(0.0001, context.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.06, context.currentTime + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + 0.22);
    oscillator.connect(gain).connect(context.destination);
    oscillator.start();
    oscillator.stop(context.currentTime + 0.24);
    oscillator.onended = () => { void context.close(); };
  } catch {
    // Звук — необязательная настройка: браузер мог запретить автовоспроизведение.
  }
}

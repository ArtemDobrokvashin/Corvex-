import { useEffect, useMemo, useRef, useState } from 'react';
import { Bell, Calendar as CalendarIcon, Clock as ClockIcon, Mail } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { DEMO_MAILS } from '../api/demoData';
import { LanguageSwitcher, useI18n } from '../i18n';
import { addDaysIso, formatIsoDate, getDueReminder, getEventsForDate, reminderKey, useCalendarStore } from '../store/calendarStore';
import { demoMailIsSeen, useSession } from '../store/sessionStore';
import Clock from './Clock';
import { useSidebarSlotContext } from './SidebarSlot';
import { StatusBadge } from './ui';

export default function WorkspaceUtilityBar({ className = '', compact = false }: { className?: string; compact?: boolean }) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { demoMode, demoReadMailIds, demoUnreadMailIds } = useSession();
  const { onLogout } = useSidebarSlotContext();
  const events = useCalendarStore((s) => s.events);
  const dismissed = useCalendarStore((s) => s.dismissedReminderKeys);
  const snoozed = useCalendarStore((s) => s.snoozedUntilMs);
  const [now, setNow] = useState(() => new Date());
  const snoozeEventReminder = useCalendarStore((s) => s.snoozeEventReminder);
  const setSelectedDate = useCalendarStore((s) => s.setSelectedDate);
  const [bellOpen, setBellOpen] = useState(false);
  const bellRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!bellOpen) return undefined;
    const onPointerDown = (e: PointerEvent) => {
      if (!bellRef.current?.contains(e.target as Node)) setBellOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setBellOpen(false);
    };
    window.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [bellOpen]);

  const todayIso = formatIsoDate(now);
  const upcomingReminders = useMemo(() => {
    const today = getEventsForDate(events, todayIso);
    const tomorrow = getEventsForDate(events, addDaysIso(todayIso, 1)).filter((event) => event.reminderMinutes === 1440);
    return [...today, ...tomorrow].filter((event) => {
      if (event.reminderMinutes === null || dismissed.includes(reminderKey(event.id, event.date))) return false;
      if (event.date !== todayIso || event.allDay) return true;
      const [hours, minutes] = event.endTime.split(':').map(Number);
      const end = new Date(now.getFullYear(), now.getMonth(), now.getDate(), hours, minutes);
      return end.getTime() >= now.getTime();
    });
  }, [events, dismissed, now, todayIso]);

  const unreadDemoMails = useMemo(
    () => (demoMode ? DEMO_MAILS.filter((m) => m.folder === 'INBOX' && !demoMailIsSeen(m, demoReadMailIds, demoUnreadMailIds)).length : 0),
    [demoMode, demoReadMailIds, demoUnreadMailIds],
  );

  const badgeCount = upcomingReminders.length + unreadDemoMails;

  return (
    <div className={`flex shrink-0 items-center gap-1.5 ${className}`}>
      {demoMode && (
        <span className={`hidden items-center gap-1.5 rounded-md border border-[#FDE68A] bg-[#FEF3C7] px-2 py-1 text-[11.5px] font-medium text-[#92400E] ${compact ? 'min-[1440px]:inline-flex' : 'sm:inline-flex'}`}>
          <span className="h-1.5 w-1.5 rounded-full bg-[#D97706]" />
          <span>{t('Демо')}</span>
          {onLogout && (
            <>
              <span aria-hidden="true">·</span>
              <button
                type="button"
                onClick={onLogout}
                className="font-semibold underline decoration-[#D97706]/50 underline-offset-2 hover:text-ink"
              >
                {t('Выйти')}
              </button>
            </>
          )}
        </span>
      )}

      <div ref={bellRef} className="relative">
        <button
          type="button"
          onClick={() => setBellOpen((v) => !v)}
          aria-label={t('Центр напоминаний')}
          title={t('Центр напоминаний')}
          aria-expanded={bellOpen}
          className="toolbar-btn relative"
        >
          <Bell size={17} strokeWidth={1.8} />
          {badgeCount > 0 && (
            <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-accent px-1 text-[9.5px] font-semibold text-white tabular-nums">
              {badgeCount}
            </span>
          )}
        </button>

        {bellOpen && (
          <div
            role="dialog"
            aria-label={t('Центр напоминаний')}
            className="absolute right-0 top-10 z-30 w-[320px] animate-scale-in rounded-xl border border-line-control bg-surface p-3 shadow-pop"
          >
            <div className="flex items-center justify-between border-b border-line-subtle pb-2">
              <span className="text-[13px] font-semibold text-ink">{t('Напоминания и события')}</span>
              <button
                type="button"
                onClick={() => {
                  setBellOpen(false);
                  navigate('/calendar');
                }}
                className="text-xs font-medium text-accent hover:text-accent-dark"
              >
                {t('Все события')}
              </button>
            </div>

            <div className="mt-2 max-h-[280px] space-y-2 overflow-y-auto">
              {upcomingReminders.map((ev) => (
                <div key={`${ev.id}:${ev.date}`} className="rounded-lg border border-line-control bg-canvas p-2.5">
                  <div className="flex items-start justify-between gap-2">
                    <span className="text-[13px] font-medium text-ink">{ev.title}</span>
                    <StatusBadge
                      tone={ev.priority === 'high' ? 'critical' : ev.priority === 'medium' ? 'warning' : 'positive'}
                    >
                      {ev.priority === 'high' ? t('Высокий') : ev.priority === 'medium' ? t('Средний') : t('Низкий')}
                    </StatusBadge>
                  </div>
                  <div className="mt-1 flex items-center gap-2 text-xs text-muted tabular-nums">
                    <ClockIcon size={12} />
                    {ev.date !== todayIso && <span>{ev.date} ·</span>}
                    <span>{ev.allDay ? t('Весь день') : `${ev.startTime} – ${ev.endTime}`}</span>
                    <span>·</span>
                    <span>
                      {ev.reminderMinutes === 0
                        ? t('В момент начала')
                        : ev.reminderMinutes === 1440
                          ? t('За 1 день')
                          : t('За {min} мин', { min: ev.reminderMinutes ?? 15 })}
                    </span>
                  </div>
                  <div className="mt-2 flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedDate(ev.date);
                        setBellOpen(false);
                        navigate('/calendar');
                      }}
                      className="inline-flex items-center gap-1 text-xs font-medium text-accent hover:text-accent-dark"
                    >
                      <CalendarIcon size={12} /> {t('Открыть')}
                    </button>
                    {getDueReminder([ev], dismissed, snoozed, now) && (
                      <button
                        type="button"
                        onClick={() => snoozeEventReminder(ev.id, 10, ev.date)}
                        className="ml-auto text-xs text-muted hover:text-ink"
                      >
                        {t('Отложить 10 мин')}
                      </button>
                    )}
                  </div>
                </div>
              ))}

              {unreadDemoMails > 0 && (
                <button
                  type="button"
                  onClick={() => {
                    setBellOpen(false);
                    navigate('/mail');
                  }}
                  className="flex w-full items-center gap-2.5 rounded-lg border border-line-control bg-surface px-2.5 py-2 text-left text-xs text-ink-soft hover:bg-canvas"
                >
                  <Mail size={14} className="shrink-0 text-accent" />
                  <span className="min-w-0 flex-1 truncate">
                    {t('Непрочитанных писем во Входящих: {count}', { count: unreadDemoMails })}
                  </span>
                </button>
              )}

              {upcomingReminders.length === 0 && unreadDemoMails === 0 && (
                <p className="py-5 text-center text-xs text-muted">{t('Активных напоминаний на сегодня нет')}</p>
              )}
            </div>
          </div>
        )}
      </div>

      <span className={compact ? 'hidden min-[1440px]:block' : ''}><Clock onOpenCalendar={() => navigate('/calendar')} /></span>
      <LanguageSwitcher className={compact ? 'hidden min-[1440px]:inline-flex' : 'hidden sm:inline-flex'} />
    </div>
  );
}

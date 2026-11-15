import { useEffect, useMemo, useRef, useState } from 'react';
import { Calendar as CalendarIcon, Clock3, Globe2 } from 'lucide-react';
import { localeTag, useI18n, type Locale } from '../i18n';
import { addDaysIso, formatIsoDate, getEventsForDate, useCalendarStore, type CalendarEventItem } from '../store/calendarStore';
export { CALENDAR_STORAGE_KEY } from '../store/calendarStore';

interface BranchZone {
  cityKey: string;
  offset: string;
  timeZone: string;
}

const BRANCH_ZONES: BranchZone[] = [
  { cityKey: 'Калининград', offset: 'UTC+2', timeZone: 'Europe/Kaliningrad' },
  { cityKey: 'Москва', offset: 'UTC+3', timeZone: 'Europe/Moscow' },
  { cityKey: 'Екатеринбург', offset: 'UTC+5', timeZone: 'Asia/Yekaterinburg' },
  { cityKey: 'Новосибирск', offset: 'UTC+7', timeZone: 'Asia/Novosibirsk' },
];

export function formatClockLabel(date: Date, locale: Locale, timeZone?: string): string {
  const parts = new Intl.DateTimeFormat(locale === 'en' ? 'en-US' : 'ru-RU', {
    hour: locale === 'en' ? 'numeric' : '2-digit',
    minute: '2-digit',
    hour12: locale === 'en',
    ...(timeZone ? { timeZone } : {}),
  }).formatToParts(date);

  const hour = parts.find((p) => p.type === 'hour')?.value ?? '00';
  const minute = parts.find((p) => p.type === 'minute')?.value ?? '00';
  const period = parts.find((p) => p.type === 'dayPeriod')?.value;

  if (locale === 'en' && period) {
    return `${hour}:${minute.padStart(2, '0')} ${period}`;
  }
  return `${hour.padStart(2, '0')}:${minute.padStart(2, '0')}`;
}

function readUpcomingMeeting(events: CalendarEventItem[], now: Date): CalendarEventItem | null {
  const today = formatIsoDate(now);
  const currentHm = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
  // A monthly series can skip a month (e.g. the 31st), so check 62 days.
  for (let offset = 0; offset <= 62; offset += 1) {
    const date = addDaysIso(today, offset);
    const first = getEventsForDate(events, date).find((event) => offset > 0 || event.allDay || event.startTime >= currentHm);
    if (first) return first;
  }
  return events.filter((event) => event.date > addDaysIso(today, 62))
    .sort((a, b) => `${a.date}T${a.startTime}`.localeCompare(`${b.date}T${b.startTime}`))[0] ?? null;
}

export function Clock({
  variant = 'light',
  showPopover = true,
  onOpenCalendar,
  className = '',
}: {
  variant?: 'light' | 'dark';
  showPopover?: boolean;
  onOpenCalendar?: () => void;
  className?: string;
}) {
  const { locale, t } = useI18n();
  const events = useCalendarStore((state) => state.events);
  const [now, setNow] = useState(() => new Date());
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 15_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    window.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const timeLabel = useMemo(() => formatClockLabel(now, locale), [now, locale]);
  const dateHeading = useMemo(
    () => new Intl.DateTimeFormat(localeTag(locale), { weekday: 'long', day: 'numeric', month: 'long' }).format(now),
    [now, locale],
  );
  const upcoming = useMemo(() => (open ? readUpcomingMeeting(events, now) : null), [events, now, open]);

  const buttonStyles =
    variant === 'dark'
      ? 'inline-flex h-8 items-center gap-1.5 rounded-lg px-2.5 text-[13px] font-medium text-sidebar-text tabular-nums transition hover:bg-white/[0.08] hover:text-white'
      : 'inline-flex h-8 items-center gap-1.5 rounded-lg border border-line-control bg-canvas px-2.5 text-[13px] font-medium text-ink-soft tabular-nums transition hover:border-[#CBD5E1] hover:bg-[#F1F5F9] hover:text-ink';

  return (
    <div ref={rootRef} className={`relative inline-flex ${className}`}>
      <button
        type="button"
        onClick={() => {
          if (showPopover) setOpen((prev) => !prev);
        }}
        aria-label={t('Текущее время')}
        aria-expanded={showPopover ? open : undefined}
        aria-haspopup={showPopover ? 'dialog' : undefined}
        className={buttonStyles}
      >
        <Clock3 size={14} strokeWidth={1.8} className={variant === 'dark' ? 'text-sidebar-muted' : 'text-faint'} />
        <span>{timeLabel}</span>
      </button>

      {showPopover && open && (
        <div
          role="dialog"
          aria-label={t('Мировое время филиалов')}
          className="absolute right-0 top-10 z-30 w-[288px] animate-scale-in rounded-xl border border-line-control bg-surface p-3.5 text-left shadow-pop"
        >
          <div className="border-b border-line-subtle pb-2.5">
            <div className="text-[11px] font-semibold uppercase tracking-[0.06em] text-faint">{t('Текущее время')}</div>
            <div className="mt-0.5 flex items-baseline justify-between gap-2">
              <span className="text-lg font-semibold text-ink tabular-nums">{timeLabel}</span>
              <span className="truncate text-xs capitalize text-muted">{dateHeading}</span>
            </div>
          </div>

          <div className="py-2.5">
            <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-[0.06em] text-faint">
              <Globe2 size={12} strokeWidth={1.8} />
              <span>{t('Мировое время филиалов')}</span>
            </div>
            <div className="space-y-1">
              {BRANCH_ZONES.map((zone) => (
                <div key={zone.timeZone} className="flex items-center justify-between rounded-lg px-2 py-1 text-[13px] hover:bg-canvas">
                  <span className="min-w-0 truncate text-ink-soft">
                    {t(zone.cityKey)} <span className="text-[11px] text-faint">({zone.offset})</span>
                  </span>
                  <span className="font-medium text-ink tabular-nums">{formatClockLabel(now, locale, zone.timeZone)}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="border-t border-line-subtle pt-2.5">
            <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-[0.06em] text-faint">
              <CalendarIcon size={12} strokeWidth={1.8} />
              <span>{t('Ближайшая встреча')}</span>
            </div>
            <div className="rounded-lg border border-line-control bg-canvas px-2.5 py-2">
              {upcoming ? (
                <>
                  <div className="truncate text-[13px] font-medium text-ink">{upcoming.title}</div>
                  <div className="mt-0.5 text-xs text-muted tabular-nums">
                    {upcoming.startTime}{upcoming.endTime ? ` – ${upcoming.endTime}` : ''} · {upcoming.date}
                  </div>
                </>
              ) : (
                <>
                  <div className="text-[13px] text-muted">{t('Ближайших встреч нет')}</div>
                </>
              )}
              {onOpenCalendar && (
                <button
                  type="button"
                  onClick={() => {
                    setOpen(false);
                    onOpenCalendar();
                  }}
                  className="mt-1.5 text-xs font-medium text-accent hover:text-accent-dark"
                >
                  {t('Открыть календарь')}
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default Clock;

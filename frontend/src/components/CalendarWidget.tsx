import { useEffect, useId, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight, Calendar as CalendarIcon } from 'lucide-react';
import { localeTag, useI18n } from '../i18n';

/**
 * Минималистичный календарь-виджет.
 * Открывается по кнопке в навигации (popover), показывает текущий месяц,
 * позволяет переключаться на предыдущий/следующий, выделяет сегодняшний день.
 * Не симулирует сохранение событий и не заявляет о серверном хранении.
 */
export function CalendarWidget({ className = '' }: { className?: string }) {
  const { locale, t } = useI18n();
  const [open, setOpen] = useState(false);
  const [viewMonth, setViewMonth] = useState(new Date());
  const wrapperRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const dialogId = useId();

  // Закрытие поповера при клике вне компонента
  useEffect(() => {
    if (!open) return;
    const onClickOutside = (event: MouseEvent) => {
      const el = wrapperRef.current;
      if (el && !el.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setOpen(false); triggerRef.current?.focus(); }
    };
    document.addEventListener('mousedown', onClickOutside);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onClickOutside);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const year = viewMonth.getFullYear();
  const month = viewMonth.getMonth();
  const today = new Date();
  const todayKey = `${today.getFullYear()}-${today.getMonth()}-${today.getDate()}`;

  const firstDay = new Date(year, month, 1);
  const lastDay = new Date(year, month + 1, 0);
  const startOffset = (firstDay.getDay() + 6) % 7;
  const totalDays = lastDay.getDate();

  const weeks: number[][] = [];
  let week: number[] = [];
  for (let i = 0; i < startOffset; i += 1) week.push(0);
  for (let d = 1; d <= totalDays; d += 1) {
    week.push(d);
    if (week.length === 7) {
      weeks.push(week);
      week = [];
    }
  }
  while (week.length > 0 && week.length < 7) week.push(0);
  if (week.length === 7) weeks.push(week);

  const monthName = new Intl.DateTimeFormat(localeTag(locale), {
    month: 'long',
    year: 'numeric',
  }).format(viewMonth);

  const weekdayNames = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(2026, 0, i + 5);
    const name = new Intl.DateTimeFormat(localeTag(locale), {
      weekday: 'short',
    }).format(d);
    return name.charAt(0).toUpperCase() + name.slice(1);
  });

  const prevMonth = () =>
    setViewMonth((m) => new Date(m.getFullYear(), m.getMonth() - 1, 1));
  const nextMonth = () =>
    setViewMonth((m) => new Date(m.getFullYear(), m.getMonth() + 1, 1));

  return (
    <div ref={wrapperRef} className={`relative ${className}`}>
      <button
        type="button"
        ref={triggerRef}
        onClick={() => setOpen((o) => !o)}
        className="flex min-h-14 w-full flex-col items-center justify-center gap-0.5 rounded-xl px-1 text-[10px] font-medium text-muted transition hover:bg-black/[0.04] hover:text-ink md:min-h-[58px] md:gap-1"
        aria-expanded={open}
        aria-controls={open ? dialogId : undefined}
        aria-haspopup="dialog"
        aria-label={t('Календарь')}
      >
        <CalendarIcon size={20} strokeWidth={1.75} />
        <span>{t('Календарь')}</span>
      </button>

      {open && (
        <div
          id={dialogId}
          className="fixed bottom-20 left-3 right-3 z-50 max-h-[70vh] overflow-y-auto panel p-3 shadow-pop md:absolute md:bottom-auto md:left-full md:right-auto md:top-0 md:ml-2 md:w-64"
          role="dialog"
          aria-label={t('Календарь')}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between mb-2">
            <button
              type="button"
              onClick={prevMonth}
              className="icon-btn-sm"
              aria-label={t('Предыдущий месяц')}
            >
              <ChevronLeft size={14} strokeWidth={1.75} />
            </button>
            <p className="text-[13px] font-medium text-ink">{monthName}</p>
            <button
              type="button"
              onClick={nextMonth}
              className="icon-btn-sm"
              aria-label={t('Следующий месяц')}
            >
              <ChevronRight size={14} strokeWidth={1.75} />
            </button>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-center border-collapse">
              <thead>
                <tr className="text-[10px] uppercase tracking-[0.08em] text-faint">
                  {weekdayNames.map((name, i) => (
                    <th key={i} className="px-1 py-1.5">
                      {name}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {weeks.map((week, wi) => (
                  <tr key={wi} className="min-h-[32px]">
                    {week.map((day, di) => {
                      const isToday = day !== 0 && todayKey === `${year}-${month}-${day}`;
                      return (
                        <td
                          key={di}
                          className={`px-1 py-1.5 rounded-lg transition text-[11px] ${
                            day === 0
                              ? 'bg-transparent'
                              : isToday
                                ? 'bg-accent-soft text-accent-dark font-medium'
                                : 'text-ink-soft hover:bg-black/[0.04]'
                          }`}
                        >
                          {day || ''}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mt-2 text-[10px] text-faint">
            {t('Локальный календарь без серверных событий.')}
          </p>
          <a href="/calendar" className="mt-2 block text-[11px] font-medium text-accent-dark" onClick={() => setOpen(false)}>
            {t('Открыть календарь')}
          </a>
        </div>
      )}
    </div>
  );
}

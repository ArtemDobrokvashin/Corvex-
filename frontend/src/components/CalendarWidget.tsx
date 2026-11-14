import { useEffect, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight, Calendar as CalendarIcon, ExternalLink } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { localeTag, useI18n } from '../i18n';

/**
 * Календарь-виджет.
 * Отображает иконку в навигации, при клике открывает поповер с календарём текущего месяца.
 * Закрывается при клике вне поповера.
 */
export function CalendarWidget({ className = '' }: { className?: string }) {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [viewMonth, setViewMonth] = useState(new Date());
  const wrapperRef = useRef<HTMLDivElement>(null);

  // Закрытие поповера при клике вне компонента
  useEffect(() => {
    if (!open) return;
    const onClickOutside = (event: MouseEvent) => {
      const el = wrapperRef.current;
      if (el && !el.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
    const d = new Date(2026, 0, i + 1);
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
    <div
      ref={wrapperRef}
      className={`relative flex flex-col items-center gap-1 ${className}`}
    >
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="icon-btn h-10 w-10"
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-label={t('Календарь')}
      >
        <CalendarIcon size={19} strokeWidth={1.75} />
      </button>
      <span className="text-[10px] font-medium text-ink-soft tracking-tight">{t('Календарь')}</span>

      {open && (
        <div
          className="absolute left-0 mt-3 w-64 max-w-[calc(100vw-32px)] rounded-2xl border border-[#e5e5ea] bg-white shadow-[0_10px_40px_rgba(0,0,0,0.10)] p-3 z-50"
          role="dialog"
          aria-modal="true"
          aria-label={t('Календарь')}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between mb-2">
            <button
              type="button"
              onClick={() => { setOpen(false); navigate('/calendar'); }}
              className="flex items-center gap-1 text-[11px] font-medium text-[#0071e3] hover:bg-[#f2f2f7] hover:text-[#004c9a] rounded-lg px-2 py-1"
            >
              <ExternalLink size={12} strokeWidth={1.75} />
              {t('Открыть календарь')}
            </button>
          </div>
          <div className="flex items-center justify-between mt-2 mb-1">
            <button
              type="button"
              onClick={prevMonth}
              className="icon-btn-sm"
              aria-label={t('Предыдущий месяц')}
            >
              <ChevronLeft size={14} strokeWidth={1.75} />
            </button>
            <p className="text-[13px] font-medium text-ink tracking-tight">{monthName}</p>
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
                <tr className="text-[10px] uppercase tracking-[0.1em] text-ink-soft">
                  {weekdayNames.map((name, i) => (
                    <th key={i} className="px-1 py-1.5">{name}</th>
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
                                ? 'bg-[#f2f2f7] text-[#0071e3] font-medium ring-1 ring-[#0071e3]'
                                : 'text-ink-soft hover:bg-[#f2f2f7]'
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
        </div>
      )}
    </div>
  );
}

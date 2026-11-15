import { useEffect, useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { localeTag, useI18n } from '../i18n';

function formatIso(year: number, monthIndex: number, day: number): string {
  return `${year}-${String(monthIndex + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export default function Calendar({
  selectedDate,
  onSelectDate,
  markersByDate,
  variant = 'light',
  className = '',
}: {
  selectedDate?: string;
  onSelectDate?: (isoDate: string) => void;
  markersByDate?: Record<string, string[]>;
  variant?: 'light' | 'dark';
  className?: string;
}) {
  const { locale, t } = useI18n();
  const today = useMemo(() => new Date(), []);
  const todayIso = formatIso(today.getFullYear(), today.getMonth(), today.getDate());

  const [viewYear, setViewYear] = useState(() => {
    if (selectedDate) {
      const y = Number(selectedDate.slice(0, 4));
      if (Number.isFinite(y) && y > 1970) return y;
    }
    return today.getFullYear();
  });
  const [viewMonth, setViewMonth] = useState(() => {
    if (selectedDate) {
      const m = Number(selectedDate.slice(5, 7)) - 1;
      if (Number.isFinite(m) && m >= 0 && m <= 11) return m;
    }
    return today.getMonth();
  });

  useEffect(() => {
    if (!selectedDate) return;
    const y = Number(selectedDate.slice(0, 4));
    const m = Number(selectedDate.slice(5, 7)) - 1;
    if (Number.isFinite(y) && Number.isFinite(m) && m >= 0 && m <= 11) {
      setViewYear(y);
      setViewMonth(m);
    }
  }, [selectedDate]);

  const prevMonth = () => {
    if (viewMonth === 0) {
      setViewMonth(11);
      setViewYear((y) => y - 1);
    } else {
      setViewMonth((m) => m - 1);
    }
  };

  const nextMonth = () => {
    if (viewMonth === 11) {
      setViewMonth(0);
      setViewYear((y) => y + 1);
    } else {
      setViewMonth((m) => m + 1);
    }
  };

  const monthTitle = useMemo(() => {
    const formatted = new Intl.DateTimeFormat(localeTag(locale), {
      month: 'long',
      year: 'numeric',
    }).format(new Date(viewYear, viewMonth, 1));
    return formatted.charAt(0).toUpperCase() + formatted.slice(1);
  }, [locale, viewMonth, viewYear]);

  const weekdays = useMemo(
    () => (locale === 'en'
      ? ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su']
      : ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс']),
    [locale],
  );

  const weeks = useMemo(() => {
    const firstOfMonth = new Date(viewYear, viewMonth, 1);
    const startOffset = (firstOfMonth.getDay() + 6) % 7; // Пн = 0 ... Вс = 6
    const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
    const cells: Array<{ day: number; iso: string; inMonth: boolean }> = [];

    const prevMonthDays = new Date(viewYear, viewMonth, 0).getDate();
    const prevY = viewMonth === 0 ? viewYear - 1 : viewYear;
    const prevM = viewMonth === 0 ? 11 : viewMonth - 1;
    for (let i = startOffset - 1; i >= 0; i -= 1) {
      const d = prevMonthDays - i;
      cells.push({ day: d, iso: formatIso(prevY, prevM, d), inMonth: false });
    }

    for (let d = 1; d <= daysInMonth; d += 1) {
      cells.push({ day: d, iso: formatIso(viewYear, viewMonth, d), inMonth: true });
    }

    const nextY = viewMonth === 11 ? viewYear + 1 : viewYear;
    const nextM = viewMonth === 11 ? 0 : viewMonth + 1;
    let trailing = 1;
    while (cells.length % 7 !== 0) {
      cells.push({ day: trailing, iso: formatIso(nextY, nextM, trailing), inMonth: false });
      trailing += 1;
    }

    const rows: Array<typeof cells> = [];
    for (let i = 0; i < cells.length; i += 7) {
      rows.push(cells.slice(i, i + 7));
    }
    return rows;
  }, [viewMonth, viewYear]);

  const isDark = variant === 'dark';

  return (
    <div className={className}>
      <div className="mb-2 flex items-center justify-between gap-1">
        <span className={`text-[13px] font-semibold ${isDark ? 'text-white' : 'text-ink'}`}>
          {monthTitle}
        </span>
        <div className="flex items-center gap-0.5">
          <button
            type="button"
            onClick={prevMonth}
            aria-label={t('Предыдущий месяц')}
            title={t('Предыдущий месяц')}
            className={`inline-flex h-7 w-7 items-center justify-center rounded-lg transition ${
              isDark
                ? 'text-sidebar-muted hover:bg-white/[0.08] hover:text-white'
                : 'text-muted hover:bg-canvas hover:text-ink'
            }`}
          >
            <ChevronLeft size={15} strokeWidth={1.9} />
          </button>
          <button
            type="button"
            onClick={nextMonth}
            aria-label={t('Следующий месяц')}
            title={t('Следующий месяц')}
            className={`inline-flex h-7 w-7 items-center justify-center rounded-lg transition ${
              isDark
                ? 'text-sidebar-muted hover:bg-white/[0.08] hover:text-white'
                : 'text-muted hover:bg-canvas hover:text-ink'
            }`}
          >
            <ChevronRight size={15} strokeWidth={1.9} />
          </button>
        </div>
      </div>

      <table className="w-full border-collapse select-none text-center tabular-nums" role="grid" aria-label={monthTitle}>
        <thead>
          <tr>
            {weekdays.map((dayName) => (
              <th
                key={dayName}
                scope="col"
                className={`pb-1.5 text-[11px] font-medium ${isDark ? 'text-sidebar-muted' : 'text-faint'}`}
              >
                {dayName}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {weeks.map((week, rowIndex) => (
            <tr key={rowIndex}>
              {week.map((cell) => {
                const isToday = cell.iso === todayIso;
                const isSelected = selectedDate === cell.iso;
                const dots = markersByDate?.[cell.iso] ?? [];

                const cellButtonClass = isSelected && !isToday
                  ? 'bg-accent text-white font-semibold'
                  : isToday
                    ? 'bg-accent-soft text-accent-dark font-semibold ring-1 ring-accent/30'
                    : cell.inMonth
                      ? isDark
                        ? 'text-sidebar-text hover:bg-white/[0.08] hover:text-white'
                        : 'text-ink-soft hover:bg-canvas hover:text-ink'
                      : isDark
                        ? 'text-sidebar-muted/45 hover:bg-white/[0.04]'
                        : 'text-faint hover:bg-canvas';

                return (
                  <td
                    key={cell.iso}
                    className={`rounded-lg align-middle ${isDark ? 'p-0' : 'p-0.5'} ${isToday ? 'bg-accent-soft' : ''}`}
                  >
                    <button
                      type="button"
                      onClick={() => onSelectDate?.(cell.iso)}
                      aria-current={isToday ? 'date' : undefined}
                      aria-pressed={onSelectDate ? isSelected : undefined}
                      className={`relative mx-auto flex ${isDark ? 'h-7 w-7' : 'h-8 w-8'} flex-col items-center justify-center rounded-lg text-[12px] leading-none transition ${cellButtonClass}`}
                    >
                      <span>{cell.day}</span>
                      {dots.length > 0 && (
                        <span className="mt-0.5 flex items-center gap-0.5" aria-hidden="true">
                          {dots.slice(0, 3).map((color, idx) => (
                            <span
                              key={`${color}-${idx}`}
                              className="h-1 w-1 rounded-full"
                              style={{ backgroundColor: isSelected && !isToday ? '#FFFFFF' : color }}
                            />
                          ))}
                        </span>
                      )}
                    </button>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

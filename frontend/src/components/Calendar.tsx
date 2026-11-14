import { useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { localeTag, useI18n } from '../i18n';

/**
 * Локальный фронтенд-календарь без серверных событий.
 * Показывает текущий месяц, позволяет переключаться на предыдущий/следующий,
 * выделяет сегодняшний день. Не симулирует сохранение событий и не заявляет,
 * что серверное хранение или уведомления уже работают.
 */
export function Calendar() {
  const { locale, t } = useI18n();
  const [viewMonth, setViewMonth] = useState(new Date());

  const year = viewMonth.getFullYear();
  const month = viewMonth.getMonth();
  const today = new Date();
  const todayKey = `${today.getFullYear()}-${today.getMonth()}-${today.getDate()}`;

  const firstDay = new Date(year, month, 1);
  const lastDay = new Date(year, month + 1, 0);
  // Понедельник = 0 (getDay(): Вс=0, Пн=1 …)
  const startOffset = (firstDay.getDay() + 6) % 7;
  const totalDays = lastDay.getDate();

  const weeks = useMemo(() => {
    const result: number[][] = [];
    let week: number[] = [];
    for (let i = 0; i < startOffset; i += 1) week.push(0);
    for (let d = 1; d <= totalDays; d += 1) {
      week.push(d);
      if (week.length === 7) {
        result.push(week);
        week = [];
      }
    }
    while (week.length > 0 && week.length < 7) week.push(0);
    if (week.length === 7) result.push(week);
    return result;
  }, [year, month, startOffset, totalDays]);

  const monthName = new Intl.DateTimeFormat(localeTag(locale), {
    month: 'long',
    year: 'numeric',
  }).format(viewMonth);

  // Имена дней недели, Пн…Вс (2026-01-05 — понедельник)
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
    <section className="panel p-5 sm:p-6">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h2 className="text-[17px] font-semibold tracking-[-0.02em]">
            {t('Календарь')}
          </h2>
          <p className="text-[13px] text-muted">{monthName}</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={prevMonth}
            className="icon-btn h-8 w-8"
            aria-label={t('Предыдущий месяц')}
          >
            <ChevronLeft size={16} />
          </button>
          <button
            type="button"
            onClick={nextMonth}
            className="icon-btn h-8 w-8"
            aria-label={t('Следующий месяц')}
          >
            <ChevronRight size={16} />
          </button>
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-center border-collapse">
          <thead>
            <tr className="text-[11px] uppercase tracking-[0.08em] text-faint">
              {weekdayNames.map((name, i) => (
                <th key={i} className="px-2 py-2">
                  {name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {weeks.map((week, wi) => (
              <tr key={wi} className="min-h-[44px]">
                {week.map((day, di) => {
                  const isToday = day !== 0 && todayKey === `${year}-${month}-${day}`;
                  return (
                    <td
                      key={di}
                      className={`px-2 py-2 rounded-lg transition ${
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
      <p className="mt-4 text-[12px] text-faint">
        {t(
          'Локальный календарь без серверных событий. Создание и сохранение встреч появятся после подключения сервера календаря.'
        )}
      </p>
    </section>
  );
}

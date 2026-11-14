import { useMemo, useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Plus,
  ChevronLeft,
  ChevronRight,
  Clock,
  Mail,
  X,
  Pencil,
  Calendar as CalendarIcon,
  ArrowUp,
  ArrowDown,
} from 'lucide-react';
import { localeTag, useI18n } from '../../i18n';
export type CalendarPriority = 'low' | 'medium' | 'high';

export interface CalendarEvent {
  id: string;
  title: string;
  date: string; // YYYY-MM-DD
  time?: string; // HH:MM
  description?: string;
  priority?: CalendarPriority;
  source?: 'manual' | 'email';
  sourceId?: string;
  sourceTitle?: string;
}

const PRIORITY_LABELS: Record<CalendarPriority, string> = {
  low: 'Низкий',
  medium: 'Средний',
  high: 'Высокий',
};

const PRIORITY_COLORS: Record<CalendarPriority, string> = {
  low: 'bg-blue-100 text-blue-700',
  medium: 'bg-[#fff7ed] text-[#9a3412]',
  high: 'bg-[#fef2f2] text-[#dc2626]',
};

const STORAGE_KEY = 'corvex.calendar.events';

function loadEvents(): CalendarEvent[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    return JSON.parse(raw);
  } catch {
    return [];
  }
}

function saveEvents(events: CalendarEvent[]) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(events));
  } catch {
    // ignore
  }
}

/** Страница календаря с событиями. Локальное хранение в localStorage. */
export default function CalendarPage() {
  const { locale, t } = useI18n();
  const [searchParams] = useSearchParams();
  const [events, setEvents] = useState<CalendarEvent[]>(() => loadEvents());
  const [viewMonth, setViewMonth] = useState(new Date());
  const [openForm, setOpenForm] = useState(false);
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [formDefaults, setFormDefaults] = useState<{
    title?: string;
    date?: string;
    description?: string;
    priority?: CalendarPriority;
  }>({});
  const [editingEvent, setEditingEvent] = useState<CalendarEvent | null>(null);

  // Создание события из письма: ?createEvent=1&title=...&date=...&description=...
  useEffect(() => {
    if (searchParams.get('createEvent') !== '1') return;
    setFormDefaults({
      title: searchParams.get('title') ?? undefined,
      date: searchParams.get('date') ?? undefined,
      description: searchParams.get('description') ?? undefined,
      priority: 'medium',
    });
    if (searchParams.get('date')) {
      setSelectedDate(searchParams.get('date'));
    }
    setEditingEvent(null);
    setOpenForm(true);
  }, [searchParams]);

  const year = viewMonth.getFullYear();
  const month = viewMonth.getMonth();
  const today = new Date();
  const todayKey = `${today.getFullYear()}-${today.getMonth()}-${today.getDate()}`;

  const firstDay = new Date(year, month, 1);
  const lastDay = new Date(year, month + 1, 0);
  const startOffset = (firstDay.getDay() + 6) % 7;
  const totalDays = lastDay.getDate();

  const eventsForMonth = events.filter((e) => {
    const d = new Date(e.date);
    return d.getFullYear() === year && d.getMonth() === month;
  });

  const eventsByDate = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>();
    eventsForMonth.forEach((e) => {
      const arr = map.get(e.date) ?? [];
      arr.push(e);
      map.set(e.date, arr);
    });
    return map;
  }, [eventsForMonth]);

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

  const upcomingEvents = useMemo(() => {
    const now = new Date();
    return events
      .filter((e) => {
        const d = new Date(`${e.date}T${e.time || '00:00'}`);
        return d >= now;
      })
      .sort((a, b) => {
        const da = new Date(`${a.date}T${a.time || '00:00'}`);
        const db = new Date(`${b.date}T${b.time || '00:00'}`);
        return da.getTime() - db.getTime();
      })
      .slice(0, 8);
  }, [events]);

  const prevMonth = () => setViewMonth((m) => new Date(m.getFullYear(), m.getMonth() - 1, 1));
  const nextMonth = () => setViewMonth((m) => new Date(m.getFullYear(), m.getMonth() + 1, 1));

  const addEvent = (newEvent: Omit<CalendarEvent, 'id'>) => {
    const event: CalendarEvent = { ...newEvent, id: crypto.randomUUID() };
    setEvents((prev) => {
      const next = [...prev, event];
      saveEvents(next);
      return next;
    });
    setOpenForm(false);
    setSelectedDate(newEvent.date);
  };

  const updateEvent = (updatedEvent: CalendarEvent) => {
    setEvents((prev) => {
      const next = prev.map((e) => (e.id === updatedEvent.id ? updatedEvent : e));
      saveEvents(next);
      return next;
    });
    setOpenForm(false);
    setEditingEvent(null);
  };

  const deleteEvent = (id: string) => {
    setEvents((prev) => {
      const next = prev.filter((e) => e.id !== id);
      saveEvents(next);
      return next;
    });
  };

  const formatEventDate = (dateStr: string, timeStr?: string) => {
    const d = new Date(dateStr);
    const datePart = d.toLocaleDateString(locale === 'ru' ? 'ru-RU' : 'en-US', {
      day: 'numeric',
      month: 'short',
    });
    if (timeStr) {
      const [h, m] = timeStr.split(':');
      return `${datePart} ${h.padStart(2, '0')}:${m}`;
    }
    return datePart;
  };

  return (
    <section className="flex flex-col h-full gap-4 p-5 sm:p-6">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h2 className="text-[16px] font-semibold tracking-tight text-ink">
            {t('Календарь')}
          </h2>
          <p className="text-[12.5px] text-ink-soft">{monthName}</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setOpenForm(true)}
            className="flex items-center gap-2 rounded-xl px-3 py-1.5 bg-[#0071e3] text-white text-sm font-medium hover:bg-[#004c9a] transition shadow-[0_1px_0_rgba(0,0,0,0.04)]"
            aria-label={t('Добавить событие')}
          >
            <Plus size={16} strokeWidth={1.75} />
            <span>{t('Добавить событие')}</span>
          </button>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={prevMonth}
              className="icon-btn-sm h-8 w-8"
              aria-label={t('Предыдущий месяц')}
            >
              <ChevronLeft size={16} strokeWidth={1.75} />
            </button>
            <button
              type="button"
              onClick={nextMonth}
              className="icon-btn-sm h-8 w-8"
              aria-label={t('Следующий месяц')}
            >
              <ChevronRight size={16} strokeWidth={1.75} />
            </button>
          </div>
        </div>
      </div>

      <div className="flex flex-col lg:flex-row gap-4">
        {/* Календарь */}
        <div className="flex-1 min-w-0">
          <div className="overflow-x-auto">
            <table className="w-full text-center border-collapse">
              <thead>
                <tr className="text-[10px] uppercase tracking-[0.1em] text-ink-soft">
                  {weekdayNames.map((name, i) => (
                    <th key={i} className="px-2 py-2.5">{name}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {weeks.map((week, wi) => (
                  <tr key={wi} className="min-h-[56px]">
                    {week.map((day, di) => {
                      const dayKey = `${year}-${month}-${day}`;
                      const isToday = day !== 0 && todayKey === dayKey;
                      const dayEvents = eventsByDate.get(dayKey) ?? [];
                      const hasEvents = dayEvents.length > 0;
                      return (
                        <td
                          key={di}
                          className={`px-2 py-2.5 rounded-xl transition cursor-pointer ${
                            day === 0
                              ? 'bg-transparent'
                              : isToday
                                ? 'bg-[#f2f2f7] text-[#0071e3] font-medium ring-1 ring-[#0071e3]'
                                : 'text-ink-soft hover:bg-[#f2f2f7]'
                          }`}
                          onClick={
                            day !== 0
                              ? () => {
                                  setSelectedDate(dayKey);
                                  setOpenForm(true);
                                }
                              : undefined
                          }
                        >
                          <div className="flex flex-col items-center h-full">
                            <span className="text-[12px] font-medium">{day || ''}</span>
                            {hasEvents && (
                              <span className="mt-1 flex h-2 w-2 items-center justify-center">
                                <span className="h-1 w-1 rounded-full bg-[#0071e3]" />
                              </span>
                            )}
                          </div>
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* Панель событий */}
        <div className="lg:w-[300px] flex-shrink-0 border-l border-[#e5e5ea] pl-4 bg-white">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-ink tracking-tight">{t('Предстоящие события')}</h3>
            {upcomingEvents.length === 0 && (
              <span className="text-[11px] text-ink-soft">{t('Нет запланированных событий')}</span>
            )}
          </div>

          {upcomingEvents.length > 0 ? (
            <div className="space-y-2">
                {upcomingEvents.map((e) => (
                <div
                  key={e.id}
                  className="flex items-center gap-3 rounded-xl p-2.5 border border-[#e5e5ea] bg-white hover:bg-[#f2f2f7] transition group shadow-[0_1px_0_rgba(0,0,0,0.04)]"
                >
                  <div className="flex flex-col items-center justify-center gap-1 shrink-0">
                    <span className="text-[10px] font-semibold text-ink-soft">
                      {new Date(e.date).toLocaleDateString(locale === 'ru' ? 'ru-RU' : 'en-US', { day: 'numeric' })}
                    </span>
                    <span className="text-[10px] text-ink-soft">{e.time || t('Весь день')}</span>
                  </div>
                  <div className="h-5 w-[1px] bg-[#e5e5ea] shrink-0" />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className={`text-sm font-medium text-ink truncate ${e.priority === 'high' ? 'font-semibold' : ''}`}>{e.title}</span>
                      {e.source === 'email' && (
                        <span className="h-2 w-2 rounded-full bg-blue-50 text-blue-600 flex items-center justify-center border border-blue-100">
                          <Mail size={10} strokeWidth={1.5} />
                        </span>
                      )}
                      {e.priority && (
                        <span className={`text-[9px] px-2 py-0.5 rounded font-bold ${PRIORITY_COLORS[e.priority]}`}>
                          {PRIORITY_LABELS[e.priority]}
                        </span>
                      )}
                    </div>
                    {e.description && (
                      <p className="mt-0.5 text-[11px] text-ink-soft line-clamp-1 truncate">{e.description}</p>
                    )}
                    {e.sourceTitle && (
                      <p className="mt-0.5 text-[10px] text-ink-soft truncate">
                        {t('Из письма')}: {e.sourceTitle}
                      </p>
                    )}
                  </div>
                  <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button
                      type="button"
                      onClick={() => { setEditingEvent(e); setOpenForm(true); }}
                      className="p-1 text-ink-soft hover:text-[#0071e3] transition hover:bg-[#f2f2f7] rounded-lg"
                      aria-label={t('Редактировать событие')}
                    >
                      <Pencil size={13} strokeWidth={1.75} />
                    </button>
                    <button
                      type="button"
                      onClick={() => deleteEvent(e.id)}
                      className="p-1 text-ink-soft hover:text-[#ff3b30] transition hover:bg-[#f2f2f7] rounded-lg"
                      aria-label={t('Удалить событие')}
                    >
                      <X size={13} strokeWidth={1.75} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center py-8 text-center">
              <CalendarIcon size={30} strokeWidth={1.75} className="text-ink-soft" />
              <p className="mt-2 text-sm text-ink-soft">{t('Нет запланированных событий')}</p>
            </div>
          )}
        </div>
      </div>

      {/* Форма добавления события */}
      {openForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/20 backdrop-blur-md" onClick={() => setOpenForm(false)}>
          <div className="w-full max-w-md animate-scale-in overflow-hidden rounded-2xl border border-[#e5e5ea] bg-white shadow-[0_20px_50px_rgba(0,0,0,0.12)]" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between border-b border-[#e5e5ea] px-4 py-3">
              <h3 className="text-[14px] font-semibold text-ink tracking-tight">
                {editingEvent ? t('Редактирование события') : selectedDate ? t('Новое событие на {date}', { date: formatEventDate(selectedDate) }) : t('Новое событие')}
              </h3>
              <button type="button" onClick={() => setOpenForm(false)} className="icon-btn-sm p-1 hover:bg-[#f2f2f7] rounded-lg" aria-label={t('Закрыть')}>
                <X size={16} strokeWidth={1.75} />
              </button>
            </div>
            <div className="p-4">
              <form onSubmit={(e) => {
                e.preventDefault();
                const formData = new FormData(e.currentTarget);
                const title = formData.get('title') as string;
                const date = formData.get('date') as string;
                const time = formData.get('time') as string;
                const description = formData.get('description') as string;
                const priority = formData.get('priority') as CalendarPriority;
                if (!title || !date) return;
                if (editingEvent) {
                  updateEvent({ ...editingEvent, title, date, time: time || undefined, description: description || undefined, priority });
                } else {
                  addEvent({ title, date, time: time || undefined, description: description || undefined, priority: priority || 'medium', source: 'manual' });
                }
              }}>
                <div className="space-y-3">
                  <div>
                    <label className="block text-[12px] font-medium text-ink-soft mb-1">{t('Название')}</label>
                    <input
                      name="title"
                      type="text"
                      required
                      defaultValue={editingEvent?.title ?? formDefaults.title ?? ''}
                      className="w-full border border-[#e5e5ea] rounded-xl px-3 py-2 text-sm text-ink shadow-[0_1px_0_rgba(0,0,0,0.04)] outline-none transition focus:border-[#0071e3] focus:ring-4 focus:ring-[#0071e3]/10"
                      placeholder={t('Например, Встреча с клиентом')}
                    />
                  </div>
                  <div className="grid gap-3 grid-cols-2">
                    <div>
                      <label className="block text-[12px] font-medium text-ink-soft mb-1">{t('Дата')}</label>
                      <input
                        name="date"
                        type="date"
                        required
                        defaultValue={editingEvent?.date ?? formDefaults.date ?? selectedDate ?? ''}
                        className="w-full border border-[#e5e5ea] rounded-xl px-3 py-2 text-sm text-ink shadow-[0_1px_0_rgba(0,0,0,0.04)] outline-none transition focus:border-[#0071e3] focus:ring-4 focus:ring-[#0071e3]/10"
                      />
                    </div>
                    <div>
                      <label className="block text-[12px] font-medium text-ink-soft mb-1">{t('Время')}</label>
                      <input
                        name="time"
                        type="time"
                        defaultValue={editingEvent?.time ?? ''}
                        className="w-full border border-[#e5e5ea] rounded-xl px-3 py-2 text-sm text-ink shadow-[0_1px_0_rgba(0,0,0,0.04)] outline-none transition focus:border-[#0071e3] focus:ring-4 focus:ring-[#0071e3]/10"
                      />
                    </div>
                  </div>
                  <div>
                    <label className="block text-[12px] font-medium text-ink-soft mb-1">{t('Приоритет')}</label>
                    <select
                      name="priority"
                      className="w-full border border-[#e5e5ea] rounded-xl px-3 py-2 text-sm text-ink shadow-[0_1px_0_rgba(0,0,0,0.04)] outline-none transition focus:border-[#0071e3] focus:ring-4 focus:ring-[#0071e3]/10"
                    >
                      <option value="low">{PRIORITY_LABELS.low}</option>
                      <option value="medium">{PRIORITY_LABELS.medium}</option>
                      <option value="high">{PRIORITY_LABELS.high}</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-[12px] font-medium text-ink-soft mb-1">{t('Описание')}</label>
                    <textarea
                      name="description"
                      rows={2}
                      defaultValue={editingEvent?.description ?? formDefaults.description ?? ''}
                      className="w-full border border-[#e5e5ea] rounded-xl px-3 py-2 text-sm text-ink shadow-[0_1px_0_rgba(0,0,0,0.04)] outline-none transition focus:border-[#0071e3] focus:ring-4 focus:ring-[#0071e3]/10"
                      placeholder={t('Что нужно сделать')}
                    />
                  </div>
                </div>
                <div className="mt-4 flex items-center justify-between">
                  <button
                    type="button"
                    onClick={() => setOpenForm(false)}
                    className="text-sm font-medium text-ink-soft hover:text-ink"
                  >
                    {t('Отмена')}
                  </button>
                  <button type="submit" className="text-sm font-medium text-white bg-[#0071e3] px-3 py-1.5 rounded-xl hover:bg-[#004c9a] transition shadow-[0_1px_0_rgba(0,0,0,0.04)]">
                    {editingEvent ? t('Сохранить изменения') : t('Сохранить')}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

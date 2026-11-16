import { useEffect, useMemo, useState } from 'react';
import {
  Bell,
  Calendar as CalendarIcon,
  Check,
  ChevronLeft,
  ChevronRight,
  Clock,
  Download,
  Mail,
  MapPin,
  Pencil,
  Plus,
  Repeat,
  Search,
  Trash2,
  Users,
} from 'lucide-react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import Calendar from '../../components/Calendar';
import SidebarSlot from '../../components/SidebarSlot';
import { cleanPersonName, StatusBadge } from '../../components/ui';
import WorkspaceUtilityBar from '../../components/WorkspaceUtilityBar';
import { localeTag, useI18n } from '../../i18n';
import {
  buildEmptyEventDraft,
  CalendarEvent,
  CalendarEventDraft,
  CalendarViewMode,
  downloadEventAsIcs,
  formatIsoDate,
  getEventsForDate,
  isValidIsoDate,
  parseIsoDate,
  useCalendarStore,
} from '../../store/calendarStore';
import ConfirmEventDelete from './ConfirmEventDelete';
import EventModal from './EventModal';

const HOURS = Array.from({ length: 24 }, (_, idx) => idx);

function eventAccentStyle(ev: CalendarEvent): { bar: string; bg: string; text: string } {
  if (ev.fromMail) {
    return { bar: '#2563EB', bg: 'bg-[#EFF6FF]', text: 'text-[#1D4ED8]' };
  }
  if (ev.priority === 'high') {
    return { bar: '#EF4444', bg: 'bg-[#FEF2F2]', text: 'text-[#B91C1C]' };
  }
  if (ev.priority === 'medium') {
    return { bar: '#F59E0B', bg: 'bg-[#FFFBEB]', text: 'text-[#92400E]' };
  }
  return { bar: '#10B981', bg: 'bg-[#F0FDF4]', text: 'text-[#15803D]' };
}

export default function CalendarPage() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();

  const events = useCalendarStore((s) => s.events);
  const selectedDate = useCalendarStore((s) => s.selectedDate);
  const setSelectedDate = useCalendarStore((s) => s.setSelectedDate);
  const viewMode = useCalendarStore((s) => s.viewMode);
  const setViewMode = useCalendarStore((s) => s.setViewMode);
  const layers = useCalendarStore((s) => s.layers);
  const toggleLayer = useCalendarStore((s) => s.toggleLayer);
  const upsertEvent = useCalendarStore((s) => s.upsertEvent);
  const deleteEvent = useCalendarStore((s) => s.deleteEvent);
  const serverSynced = useCalendarStore((s) => s.serverSynced);
  const syncing = useCalendarStore((s) => s.syncing);
  const syncError = useCalendarStore((s) => s.syncError);
  const clearSyncError = useCalendarStore((s) => s.clearSyncError);
  const loadServerEvents = useCalendarStore((s) => s.loadServerEvents);

  const [searchQuery, setSearchQuery] = useState('');
  const [modalDraft, setModalDraft] = useState<CalendarEventDraft | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<CalendarEvent | null>(null);

  const todayIso = formatIsoDate(new Date());

  useEffect(() => {
    const draft = (location.state as { eventDraft?: CalendarEventDraft } | null)?.eventDraft;
    if (!draft) return;
    setModalDraft(draft);
    navigate(`${location.pathname}${location.search}`, { replace: true, state: null });
  }, [location.pathname, location.search, location.state, navigate]);

  // Handle URL params (?date=... & ?createEvent=1)
  useEffect(() => {
    const paramDate = searchParams.get('date');
    if (isValidIsoDate(paramDate)) {
      setSelectedDate(paramDate);
    }
    if (searchParams.get('createEvent') === '1') {
      const targetDate = isValidIsoDate(paramDate) ? paramDate : selectedDate;
      const requestedTime = searchParams.get('startTime');
      const draft = buildEmptyEventDraft(
        targetDate,
        requestedTime && /^([01]\d|2[0-3]):[0-5]\d$/.test(requestedTime) ? requestedTime : '11:00',
      );
      if (searchParams.get('title')) draft.title = searchParams.get('title') || '';
      if (searchParams.get('fromMail') === '1') draft.fromMail = true;
      if (searchParams.get('mailId')) draft.sourceMailId = searchParams.get('mailId') || undefined;
      if (searchParams.get('sender')) draft.sourceMailSender = searchParams.get('sender') || undefined;
      setModalDraft(draft);

      const next = new URLSearchParams(searchParams);
      next.delete('createEvent');
      next.delete('title');
      next.delete('startTime');
      next.delete('fromMail');
      next.delete('mailId');
      next.delete('sender');
      setSearchParams(next, { replace: true });
    }
  // Re-apply a deep-link only when the URL changes, not when the user selects another day.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams, setSearchParams, setSelectedDate]);

  const selectedDateObj = useMemo(() => parseIsoDate(selectedDate), [selectedDate]);
  const viewYear = selectedDateObj.getFullYear();
  const viewMonth = selectedDateObj.getMonth();

  const matchesActiveLayersAndSearch = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return (ev: CalendarEvent): boolean => {
      const priorityEnabled = layers[ev.priority];
      const mailAllowed = !ev.fromMail || layers.fromMail;
      if (!priorityEnabled || !mailAllowed) return false;
      if (!q) return true;
      return (
        ev.title.toLowerCase().includes(q)
        || ev.description.toLowerCase().includes(q)
        || ev.location.toLowerCase().includes(q)
        || ev.attendees.some((a) => a.toLowerCase().includes(q))
      );
    };
  }, [layers, searchQuery]);

  const filteredEvents = useMemo(
    () => events.filter(matchesActiveLayersAndSearch),
    [events, matchesActiveLayersAndSearch],
  );

  // Month grid (42 cells / 6 weeks or 35 cells)
  const monthCells = useMemo(() => {
    const firstOfMonth = new Date(viewYear, viewMonth, 1);
    const startOffset = (firstOfMonth.getDay() + 6) % 7;
    const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
    const prevMonthDays = new Date(viewYear, viewMonth, 0).getDate();
    const list: Array<{ iso: string; day: number; inMonth: boolean }> = [];

    for (let i = startOffset - 1; i >= 0; i -= 1) {
      const d = prevMonthDays - i;
      list.push({ iso: formatIsoDate(new Date(viewYear, viewMonth - 1, d)), day: d, inMonth: false });
    }
    for (let d = 1; d <= daysInMonth; d += 1) {
      list.push({ iso: formatIsoDate(new Date(viewYear, viewMonth, d)), day: d, inMonth: true });
    }
    let trailing = 1;
    while (list.length % 7 !== 0 || list.length < 35) {
      list.push({ iso: formatIsoDate(new Date(viewYear, viewMonth + 1, trailing)), day: trailing, inMonth: false });
      trailing += 1;
    }
    return list;
  }, [viewMonth, viewYear]);

  // Week days around selectedDate (Mon..Sun)
  const weekDays = useMemo(() => {
    const base = parseIsoDate(selectedDate);
    const offset = (base.getDay() + 6) % 7;
    const monday = new Date(base.getFullYear(), base.getMonth(), base.getDate() - offset);
    return Array.from({ length: 7 }, (_, idx) => {
      const dt = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + idx);
      return {
        iso: formatIsoDate(dt),
        dayNum: dt.getDate(),
        shortLabel: new Intl.DateTimeFormat(localeTag(locale), { weekday: 'short' }).format(dt),
      };
    });
  }, [locale, selectedDate]);

  // Agenda upcoming 30 days from selectedDate
  const agendaGroups = useMemo(() => {
    const base = parseIsoDate(selectedDate);
    const groups: Array<{ iso: string; label: string; items: CalendarEvent[] }> = [];
    for (let offset = 0; offset < 30; offset += 1) {
      const dt = new Date(base.getFullYear(), base.getMonth(), base.getDate() + offset);
      const iso = formatIsoDate(dt);
      const items = getEventsForDate(filteredEvents, iso);
      if (items.length > 0) {
        groups.push({
          iso,
          label: new Intl.DateTimeFormat(localeTag(locale), {
            weekday: 'long',
            day: 'numeric',
            month: 'long',
          }).format(dt),
          items,
        });
      }
    }
    return groups;
  }, [filteredEvents, locale, selectedDate]);

  const markersByDate = useMemo(() => {
    const map: Record<string, string[]> = {};
    for (const cell of monthCells) {
      const dayItems = getEventsForDate(filteredEvents, cell.iso);
      if (dayItems.length > 0) {
        map[cell.iso] = dayItems.map((ev) => eventAccentStyle(ev).bar);
      }
    }
    return map;
  }, [filteredEvents, monthCells]);

  const inspectorEvents = useMemo(
    () => getEventsForDate(filteredEvents, selectedDate),
    [filteredEvents, selectedDate],
  );

  const periodHeading = useMemo(() => {
    if (viewMode === 'day') {
      return new Intl.DateTimeFormat(localeTag(locale), {
        day: 'numeric',
        month: 'long',
        year: 'numeric',
      }).format(selectedDateObj);
    }
    const formatted = new Intl.DateTimeFormat(localeTag(locale), {
      month: 'long',
      year: 'numeric',
    }).format(selectedDateObj);
    return formatted.charAt(0).toUpperCase() + formatted.slice(1);
  }, [locale, selectedDateObj, viewMode]);

  const shiftPeriod = (direction: -1 | 1) => {
    const d = parseIsoDate(selectedDate);
    if (viewMode === 'day') {
      d.setDate(d.getDate() + direction);
    } else if (viewMode === 'week') {
      d.setDate(d.getDate() + direction * 7);
    } else {
      const day = d.getDate();
      d.setDate(1);
      d.setMonth(d.getMonth() + direction);
      d.setDate(Math.min(day, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()));
    }
    setSelectedDate(formatIsoDate(d));
  };

  const weekdayHeaders = useMemo(
    () => (locale === 'en'
      ? ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
      : ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс']),
    [locale],
  );

  const selectedDayTitle = useMemo(() => {
    return new Intl.DateTimeFormat(localeTag(locale), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
    }).format(selectedDateObj);
  }, [locale, selectedDateObj]);

  const openEditModal = (occurrence: CalendarEvent) => {
    const ev = events.find((event) => event.id === occurrence.id) ?? occurrence;
    setModalDraft({
      id: ev.id,
      title: ev.title,
      date: ev.date,
      startTime: ev.startTime,
      endTime: ev.endTime,
      allDay: ev.allDay,
      priority: ev.priority,
      reminderMinutes: ev.reminderMinutes,
      recurrence: ev.recurrence,
      location: ev.location,
      attendees: ev.attendees,
      busyStatus: ev.busyStatus,
      description: ev.description,
      // Ревизия нужна серверу, чтобы не перезаписать правку из другого окна.
      revision: ev.revision,
      fromMail: ev.fromMail,
      sourceMailId: ev.sourceMailId,
      sourceMailSubject: ev.sourceMailSubject,
      sourceMailSender: ev.sourceMailSender,
    });
  };

  return (
    <div className="flex h-full min-w-0 flex-1 flex-col bg-surface">
      {/* SidebarSlot: тёмная колонка с кнопкой + Новое событие, мини-календарём и слоями */}
      <SidebarSlot>
        <div className="space-y-4 px-3 py-3">
          <button
            type="button"
            onClick={() => setModalDraft(buildEmptyEventDraft(selectedDate))}
            className="flex h-[38px] w-full items-center justify-center gap-2 rounded-lg bg-accent px-3 text-[13.5px] font-medium text-white transition hover:bg-accent-dark"
          >
            <Plus size={16} strokeWidth={2.1} />
            <span>{t('Новое событие')}</span>
          </button>

          <div className="rounded-xl border border-sidebar-line bg-[#111B30] p-2.5">
            <Calendar
              variant="dark"
              selectedDate={selectedDate}
              onSelectDate={setSelectedDate}
              markersByDate={markersByDate}
            />
          </div>

          <div>
            <p className="sidebar-section-label mb-1.5 px-1.5">{t('Слои и приоритеты')}</p>
            <div className="space-y-0.5">
              {([
                { key: 'high', label: t('Высокий приоритет'), color: '#EF4444' },
                { key: 'medium', label: t('Средний приоритет'), color: '#F59E0B' },
                { key: 'low', label: t('Низкий приоритет'), color: '#10B981' },
                { key: 'fromMail', label: t('Из писем'), color: '#2563EB' },
              ] as const).map((layer) => {
                const active = layers[layer.key];
                return (
                  <button
                    key={layer.key}
                    type="button"
                    aria-pressed={active}
                    onClick={() => toggleLayer(layer.key)}
                    className="flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left text-[13px] text-sidebar-text transition hover:bg-sidebar-hover hover:text-white"
                  >
                    <span
                      className="flex h-4 w-4 shrink-0 items-center justify-center rounded border"
                      style={{
                        backgroundColor: active ? layer.color : 'transparent',
                        borderColor: layer.color,
                      }}
                    >
                      {active && <Check size={11} strokeWidth={2.6} className="text-white" />}
                    </span>
                    <span className="min-w-0 flex-1 truncate">{layer.label}</span>
                  </button>
                );
              })}
            </div>
          </div>
          <p className="px-1.5 text-[11px] leading-relaxed text-sidebar-muted">
            {serverSynced
              ? t('Серверные напоминания включены: они приходят на почту даже при закрытом браузере.')
              : t('События сохраняются только в этом браузере и не синхронизируются. Напоминания работают, пока приложение открыто.')}
          </p>
        </div>
      </SidebarSlot>

      {/* Верхний тулбар 56px (#FFFFFF, border-b #E5E7EB) */}
      {syncError && (
        <div role="alert" className="z-10 flex shrink-0 items-start justify-between gap-3 border-b border-amber-200 bg-amber-50 px-4 py-2 text-[12px] text-amber-900">
          <span className="min-w-0">{t(syncError)}</span>
          <span className="flex shrink-0 items-center gap-2">
            {serverSynced && (
              <button type="button" onClick={() => { void loadServerEvents(); }} className="font-medium underline">
                {t('Обновить')}
              </button>
            )}
            <button type="button" onClick={clearSyncError} aria-label={t('Закрыть')} className="px-1 text-amber-700">×</button>
          </span>
        </div>
      )}
      <header className="z-10 flex h-14 shrink-0 items-center justify-between gap-3 border-b border-line bg-surface px-4">
        <div className="flex min-w-0 flex-1 items-center gap-2.5">
          {/* Search */}
          <div className="relative hidden w-[160px] shrink-0 xl:block 2xl:w-[220px]">
            <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
            <input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t('Поиск по событиям…')}
              aria-label={t('Поиск по событиям')}
              className="field h-9 bg-canvas pl-8 text-[13px]"
            />
          </div>

          {/* Period switcher */}
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => shiftPeriod(-1)}
              aria-label={t('Назад')}
              className="toolbar-btn"
            >
              <ChevronLeft size={17} />
            </button>
            <h1 className="min-w-[138px] text-center text-[15px] font-semibold text-ink tabular-nums">
              {periodHeading}
            </h1>
            <button
              type="button"
              onClick={() => shiftPeriod(1)}
              aria-label={t('Вперёд')}
              className="toolbar-btn"
            >
              <ChevronRight size={17} />
            </button>
            <button
              type="button"
              onClick={() => setSelectedDate(todayIso)}
              className="btn-secondary ml-1 hidden h-8 px-2.5 text-xs min-[380px]:inline-flex"
            >
              {t('Сегодня')}
            </button>
          </div>

          {/* Segmented view switcher */}
          <div
            role="tablist"
            aria-label={t('Режим календаря')}
            className="hidden items-center rounded-lg border border-line-control bg-canvas p-0.5 xl:inline-flex"
          >
            {([
              { id: 'month', label: t('Месяц') },
              { id: 'week', label: t('Неделя') },
              { id: 'day', label: t('День') },
              { id: 'agenda', label: t('Повестка') },
            ] as Array<{ id: CalendarViewMode; label: string }>).map((mode) => {
              const active = viewMode === mode.id;
              return (
                <button
                  key={mode.id}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => setViewMode(mode.id)}
                  className={`h-7 rounded-md px-2.5 text-xs font-medium transition ${
                    active
                      ? 'bg-surface text-ink shadow-soft'
                      : 'text-muted hover:text-ink'
                  }`}
                >
                  {mode.label}
                </button>
              );
            })}
          </div>
          <select aria-label={t('Режим календаря')} value={viewMode} onChange={(event) => setViewMode(event.target.value as CalendarViewMode)} className="field hidden h-8 w-auto px-2 text-xs md:block xl:hidden">
            <option value="month">{t('Месяц')}</option><option value="week">{t('Неделя')}</option><option value="day">{t('День')}</option><option value="agenda">{t('Повестка')}</option>
          </select>
        </div>

        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setModalDraft(buildEmptyEventDraft(selectedDate))}
            aria-label={t('Новое событие')}
            className="btn-primary h-9 px-2.5 text-[13px]"
          >
            <Plus size={15} /> <span className="hidden 2xl:inline">{t('Событие')}</span>
          </button>
          <WorkspaceUtilityBar compact className="hidden lg:flex" />
        </div>
      </header>
      <div className="flex shrink-0 items-center gap-2 border-b border-line px-3 py-2 xl:hidden">
        <input value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} placeholder={t('Поиск по событиям…')} aria-label={t('Поиск по событиям')} className="field h-9 min-w-0 flex-1" />
        <select aria-label={t('Режим календаря')} value={viewMode} onChange={(event) => setViewMode(event.target.value as CalendarViewMode)} className="field h-9 w-auto px-2 md:hidden">
          <option value="month">{t('Месяц')}</option><option value="week">{t('Неделя')}</option><option value="day">{t('День')}</option><option value="agenda">{t('Повестка')}</option>
        </select>
      </div>

      {/* Main split: Calendar grid/timeline + 320px Day Inspector */}
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden xl:flex-row">
        {/* Center calendar view */}
        <div className="flex min-w-0 flex-1 flex-col overflow-hidden bg-surface">
          {viewMode === 'month' && (
            <div className="flex min-h-0 flex-1 flex-col">
              <div className="grid shrink-0 grid-cols-7 border-b border-line bg-canvas">
                {weekdayHeaders.map((label) => (
                  <div
                    key={label}
                    className="border-r border-line py-2 text-center text-[11.5px] font-semibold uppercase tracking-[0.04em] text-muted last:border-r-0"
                  >
                    {label}
                  </div>
                ))}
              </div>

              <div
                className="grid min-h-0 flex-1 grid-cols-7 overflow-y-auto"
                style={{ gridAutoRows: 'minmax(108px, 1fr)' }}
              >
                {monthCells.map((cell) => {
                  const dayItems = getEventsForDate(filteredEvents, cell.iso);
                  const isToday = cell.iso === todayIso;
                  const isSelected = cell.iso === selectedDate;
                  return (
                    <div
                      key={cell.iso}
                      onClick={() => setSelectedDate(cell.iso)}
                      onDoubleClick={() => setModalDraft(buildEmptyEventDraft(cell.iso))}
                      className={`group flex cursor-pointer flex-col border-b border-r border-line p-2 transition last:border-r-0 ${
                        isSelected
                          ? 'bg-[#EEF4FF]/55'
                          : cell.inMonth
                            ? 'bg-surface hover:bg-canvas/70'
                            : 'bg-canvas/55 text-faint hover:bg-canvas'
                      }`}
                    >
                      <div className="mb-1.5 flex items-center justify-between">
                        <span
                          className={`inline-flex h-6 min-w-6 items-center justify-center rounded-md px-1.5 text-xs font-semibold tabular-nums ${
                            isToday
                              ? 'bg-accent text-white'
                              : isSelected
                                ? 'bg-[#DBEAFE] text-accent-dark'
                                : cell.inMonth
                                  ? 'text-ink'
                                  : 'text-faint'
                          }`}
                        >
                          {cell.day}
                        </span>
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            setModalDraft(buildEmptyEventDraft(cell.iso));
                          }}
                          aria-label={`${t('Добавить событие')}: ${cell.iso}`}
                          className="rounded p-0.5 text-faint opacity-0 transition hover:bg-surface hover:text-accent group-hover:opacity-100"
                        >
                          <Plus size={13} />
                        </button>
                      </div>

                      <div className="space-y-1">
                        {dayItems.slice(0, 3).map((ev) => {
                          const st = eventAccentStyle(ev);
                          return (
                            <button
                              key={`${ev.id}-${cell.iso}`}
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                setSelectedDate(cell.iso);
                                openEditModal(ev);
                              }}
                              className={`relative flex w-full items-center gap-1.5 overflow-hidden rounded-md py-1 pl-2.5 pr-1.5 text-left text-[11.5px] font-medium transition hover:brightness-95 ${st.bg} ${st.text}`}
                            >
                              <span
                                className="absolute bottom-0 left-0 top-0 w-[3px]"
                                style={{ backgroundColor: st.bar }}
                                aria-hidden="true"
                              />
                              {!ev.allDay && (
                                <span className="shrink-0 text-[10.5px] opacity-80 tabular-nums">
                                  {ev.startTime}
                                </span>
                              )}
                              <span className="min-w-0 flex-1 truncate">{ev.title}</span>
                            </button>
                          );
                        })}
                        {dayItems.length > 3 && (
                          <div className="px-1 text-[11px] font-medium text-muted">
                            {t('+ ещё {count}', { count: dayItems.length - 3 })}
                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {viewMode === 'week' && (
            <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
              <div className="grid shrink-0 grid-cols-[64px_repeat(7,1fr)] border-b border-line bg-canvas">
                <div className="border-r border-line py-2 text-center text-[11px] text-faint tabular-nums">
                  {new Intl.DateTimeFormat(localeTag(locale), { timeZoneName: 'short' }).formatToParts(new Date()).find((part) => part.type === 'timeZoneName')?.value ?? 'GMT'}
                </div>
                {weekDays.map((wd) => {
                  const isToday = wd.iso === todayIso;
                  const isSelected = wd.iso === selectedDate;
                  return (
                    <button
                      key={wd.iso}
                      type="button"
                      onClick={() => setSelectedDate(wd.iso)}
                      className={`border-r border-line py-2 text-center transition last:border-r-0 ${
                        isSelected ? 'bg-[#EEF4FF]' : 'hover:bg-surface'
                      }`}
                    >
                      <div className="text-[11px] font-medium uppercase text-muted">{wd.shortLabel}</div>
                      <div
                        className={`mx-auto mt-0.5 inline-flex h-6 w-6 items-center justify-center rounded-md text-xs font-semibold tabular-nums ${
                          isToday ? 'bg-accent text-white' : 'text-ink'
                        }`}
                      >
                        {wd.dayNum}
                      </div>
                    </button>
                  );
                })}
              </div>

              <div className="scroll-area flex-1">
                {HOURS.map((hour) => {
                  const hh = `${String(hour).padStart(2, '0')}:00`;
                  return (
                    <div
                      key={hour}
                      className="grid min-h-[58px] grid-cols-[64px_repeat(7,1fr)] border-b border-line-subtle"
                    >
                      <div className="border-r border-line px-2 py-1.5 text-right text-[11px] text-faint tabular-nums">
                        {hh}
                      </div>
                      {weekDays.map((wd) => {
                        const slotEvents = getEventsForDate(filteredEvents, wd.iso).filter((ev) => {
                          if (ev.allDay) return hour === 0;
                          const startH = Number(ev.startTime.slice(0, 2));
                          return startH === hour;
                        });
                        return (
                          <div
                            key={`${wd.iso}-${hour}`}
                            onClick={() => {
                              setSelectedDate(wd.iso);
                              setModalDraft(buildEmptyEventDraft(wd.iso, hh));
                            }}
                            className="cursor-pointer space-y-1 border-r border-line p-1 transition hover:bg-canvas/70 last:border-r-0"
                          >
                            {slotEvents.map((ev) => {
                              const st = eventAccentStyle(ev);
                              return (
                                <button
                                  key={ev.id}
                                  type="button"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setSelectedDate(wd.iso);
                                    openEditModal(ev);
                                  }}
                                  className={`relative block w-full overflow-hidden rounded-md py-1 pl-2.5 pr-1.5 text-left text-[11px] font-medium ${st.bg} ${st.text}`}
                                >
                                  <span
                                    className="absolute bottom-0 left-0 top-0 w-[3px]"
                                    style={{ backgroundColor: st.bar }}
                                  />
                                  <div className="truncate font-semibold">{ev.title}</div>
                                  <div className="text-[10px] opacity-80 tabular-nums">
                                    {ev.allDay ? t('Весь день') : `${ev.startTime}–${ev.endTime}`}
                                  </div>
                                </button>
                              );
                            })}
                          </div>
                        );
                      })}
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {viewMode === 'day' && (
            <div className="scroll-area flex-1 divide-y divide-line-subtle">
              {HOURS.map((hour) => {
                const hh = `${String(hour).padStart(2, '0')}:00`;
                const slotEvents = inspectorEvents.filter((ev) => {
                  if (ev.allDay) return hour === 0;
                  return Number(ev.startTime.slice(0, 2)) === hour;
                });
                return (
                  <div
                    key={hour}
                    onClick={() => setModalDraft(buildEmptyEventDraft(selectedDate, hh))}
                    className="flex min-h-[64px] cursor-pointer transition hover:bg-canvas/60"
                  >
                    <div className="w-20 shrink-0 border-r border-line px-3 py-2 text-right text-xs text-faint tabular-nums">
                      {hh}
                    </div>
                    <div className="flex-1 space-y-1.5 p-2">
                      {slotEvents.map((ev) => {
                        const st = eventAccentStyle(ev);
                        return (
                          <button
                            key={ev.id}
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation();
                              openEditModal(ev);
                            }}
                            className={`relative flex w-full items-center justify-between gap-3 overflow-hidden rounded-lg py-2 pl-3.5 pr-3 text-left ${st.bg} ${st.text}`}
                          >
                            <span
                              className="absolute bottom-0 left-0 top-0 w-[3px]"
                              style={{ backgroundColor: st.bar }}
                            />
                            <div className="min-w-0">
                              <div className="truncate text-[13px] font-semibold">{ev.title}</div>
                              {ev.location && (
                                <div className="mt-0.5 truncate text-xs opacity-80">{ev.location}</div>
                              )}
                            </div>
                            <span className="shrink-0 text-xs font-medium tabular-nums">
                              {ev.allDay ? t('Весь день') : `${ev.startTime} – ${ev.endTime}`}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {viewMode === 'agenda' && (
            <div className="scroll-area flex-1 p-5">
              {agendaGroups.length === 0 ? (
                <div className="mx-auto max-w-md rounded-xl border border-dashed border-line-control bg-canvas p-8 text-center">
                  <CalendarIcon size={24} className="mx-auto mb-2 text-faint" />
                  <p className="text-sm font-semibold text-ink">{t('Предстоящих событий не найдено')}</p>
                  <p className="mt-1 text-xs text-muted">
                    {t('Измените фильтры слоёв слева или добавьте новое событие')}
                  </p>
                </div>
              ) : (
                <div className="mx-auto max-w-3xl space-y-5">
                  {agendaGroups.map((group) => (
                    <section key={group.iso} className="rounded-xl border border-line bg-surface overflow-hidden">
                      <header className="flex items-center justify-between border-b border-line bg-canvas px-4 py-2.5">
                        <span className="text-[13px] font-semibold capitalize text-ink">{group.label}</span>
                        <span className="text-xs text-muted tabular-nums">{group.iso}</span>
                      </header>
                      <div className="divide-y divide-line-subtle">
                        {group.items.map((ev) => {
                          const st = eventAccentStyle(ev);
                          return (
                            <div
                              key={`${ev.id}-${group.iso}`}
                              onClick={() => {
                                setSelectedDate(group.iso);
                                openEditModal(ev);
                              }}
                              className="flex cursor-pointer items-center gap-4 px-4 py-3 transition hover:bg-canvas/70"
                            >
                              <span
                                className="h-8 w-1 shrink-0 rounded-full"
                                style={{ backgroundColor: st.bar }}
                              />
                              <span className="w-28 shrink-0 text-xs font-medium text-muted tabular-nums">
                                {ev.allDay ? t('Весь день') : `${ev.startTime} – ${ev.endTime}`}
                              </span>
                              <div className="min-w-0 flex-1">
                                <div className="truncate text-[13.5px] font-semibold text-ink">{ev.title}</div>
                                {ev.location && (
                                  <div className="truncate text-xs text-muted">{ev.location}</div>
                                )}
                              </div>
                              <StatusBadge
                                tone={ev.priority === 'high' ? 'critical' : ev.priority === 'medium' ? 'warning' : 'positive'}
                              >
                                {ev.priority === 'high' ? t('Высокий') : ev.priority === 'medium' ? t('Средний') : t('Низкий')}
                              </StatusBadge>
                            </div>
                          );
                        })}
                      </div>
                    </section>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>

        {/* Compact day inspector on screens without the 320px column. */}
        <section aria-label={t('Инспектор выбранного дня')} className="max-h-[148px] shrink-0 overflow-y-auto border-t border-line bg-surface px-3 py-2 xl:hidden">
          <h2 className="text-xs font-semibold capitalize text-ink">{selectedDayTitle}</h2>
          {inspectorEvents.length === 0 ? <p className="mt-1 text-xs text-muted">{t('На этот день событий нет')}</p> : (
            <div className="mt-1 space-y-1">{inspectorEvents.map((event) => <button key={`${event.id}-${selectedDate}`} type="button" onClick={() => openEditModal(event)} className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs hover:bg-canvas"><span className="h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: eventAccentStyle(event).bar }} /><span className="shrink-0 font-medium text-muted tabular-nums">{event.allDay ? t('Весь день') : event.startTime}</span><span className="min-w-0 truncate font-medium text-ink">{event.title}</span></button>)}</div>
          )}
        </section>

        {/* Правая инспектор-колонка 320px (border-l #E5E7EB) */}
        <aside
          aria-label={t('Инспектор выбранного дня')}
          className="hidden w-[320px] shrink-0 flex-col border-l border-line bg-surface xl:flex"
        >
          <header className="flex items-center justify-between border-b border-line px-4 py-3.5">
            <div className="min-w-0">
              <p className="text-[11px] font-semibold uppercase tracking-[0.06em] text-muted">
                {t('Расписание дня')}
              </p>
              <h2 className="mt-0.5 truncate text-[14px] font-semibold capitalize text-ink">
                {selectedDayTitle}
              </h2>
            </div>
            <button
              type="button"
              onClick={() => setModalDraft(buildEmptyEventDraft(selectedDate))}
              className="btn-secondary h-8 px-2.5 text-xs"
            >
              <Plus size={14} /> {t('Добавить')}
            </button>
          </header>

          <div className="scroll-area flex-1 space-y-3 p-4">
            {inspectorEvents.length === 0 ? (
              <div className="rounded-xl border border-dashed border-line-control bg-canvas px-4 py-8 text-center">
                <CalendarIcon size={20} className="mx-auto mb-2 text-faint" />
                <p className="text-[13px] font-medium text-ink-soft">{t('На этот день событий нет')}</p>
                <p className="mt-1 text-xs text-muted">
                  {t('Дважды нажмите на ячейку сетки или кнопку «Добавить»')}
                </p>
              </div>
            ) : (
              inspectorEvents.map((ev) => {
                const st = eventAccentStyle(ev);
                return (
                  <article
                    key={`${ev.id}-${selectedDate}`}
                    className="relative rounded-xl border border-line-control bg-surface p-3.5 transition hover:border-[#CBD5E1]"
                  >
                    <span
                      className="absolute bottom-3 left-0 top-3 w-[3px] rounded-r"
                      style={{ backgroundColor: st.bar }}
                      aria-hidden="true"
                    />
                    <div className="pl-2">
                      <div className="flex items-start justify-between gap-2">
                        <h3 className="text-[13.5px] font-semibold leading-snug text-ink">{ev.title}</h3>
                        <div className="flex shrink-0 items-center gap-0.5">
                          <button
                            type="button"
                            onClick={() => openEditModal(ev)}
                            aria-label={`${t('Редактировать')}: ${ev.title}`}
                            className="rounded p-1 text-faint hover:bg-canvas hover:text-ink"
                          >
                            <Pencil size={13.5} />
                          </button>
                          <button
                            type="button"
                            onClick={() => downloadEventAsIcs(events.find((event) => event.id === ev.id) ?? ev)}
                            aria-label={`${t('Экспорт .ics')}: ${ev.title}`}
                            title={t('Экспорт .ics')}
                            className="rounded p-1 text-faint hover:bg-canvas hover:text-ink"
                          >
                            <Download size={13.5} />
                          </button>
                          <button
                            type="button"
                            onClick={() => setDeleteTarget(ev)}
                            aria-label={`${t('Удалить')}: ${ev.title}`}
                            className="rounded p-1 text-faint hover:bg-danger-soft hover:text-danger"
                          >
                            <Trash2 size={13.5} />
                          </button>
                        </div>
                      </div>

                      <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted tabular-nums">
                        <span className="inline-flex items-center gap-1 font-medium text-ink-soft">
                          <Clock size={12} className="text-faint" />
                          {ev.allDay ? t('Весь день') : `${ev.startTime} – ${ev.endTime}`}
                        </span>
                        {ev.reminderMinutes !== null && (
                          <span className="inline-flex items-center gap-1">
                            <Bell size={12} className="text-faint" />
                            {ev.reminderMinutes === 0
                              ? t('В момент начала')
                              : t('За {min} мин', { min: ev.reminderMinutes })}
                          </span>
                        )}
                        {ev.recurrence !== 'none' && (
                          <span className="inline-flex items-center gap-1">
                            <Repeat size={12} className="text-faint" />
                            {t('Повтор')}
                          </span>
                        )}
                      </div>

                      {ev.location && (
                        <div className="mt-1.5 flex items-center gap-1.5 text-xs text-muted">
                          <MapPin size={12.5} className="shrink-0 text-faint" />
                          <span className="truncate">{ev.location}</span>
                        </div>
                      )}

                      {ev.attendees.length > 0 && (
                        <div className="mt-1.5 flex items-start gap-1.5 text-xs text-muted">
                          <Users size={12.5} className="mt-0.5 shrink-0 text-faint" />
                          <span className="line-clamp-2">{ev.attendees.join(', ')}</span>
                        </div>
                      )}

                      {ev.description && (
                        <p className="mt-2 line-clamp-3 rounded-lg bg-canvas px-2.5 py-2 text-xs leading-relaxed text-ink-soft">
                          {ev.description}
                        </p>
                      )}

                      <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
                        <StatusBadge
                          tone={ev.priority === 'high' ? 'critical' : ev.priority === 'medium' ? 'warning' : 'positive'}
                        >
                          {ev.priority === 'high' ? t('Высокий') : ev.priority === 'medium' ? t('Средний') : t('Низкий')}
                        </StatusBadge>

                        {ev.fromMail && (
                          ev.sourceMailId ? (
                            <button
                              type="button"
                              onClick={() => navigate(`/mail?mailId=${encodeURIComponent(ev.sourceMailId!)}`)}
                              className="inline-flex items-center gap-1 rounded-md bg-[#EFF6FF] px-2 py-0.5 text-[11px] font-medium text-[#1D4ED8] hover:underline"
                            >
                              <Mail size={11} />
                              <span>
                                {ev.sourceMailSender
                                  ? `${t('Письмо от')} ${cleanPersonName(ev.sourceMailSender)}`
                                  : t('Открыть письмо')}
                              </span>
                            </button>
                          ) : (
                            <StatusBadge tone="info">
                              <Mail size={11} /> {t('Из письма')}
                            </StatusBadge>
                          )
                        )}
                      </div>
                    </div>
                  </article>
                );
              })
            )}
          </div>
        </aside>
      </div>

      {deleteTarget && (
        <ConfirmEventDelete
          event={deleteTarget}
          onCancel={() => setDeleteTarget(null)}
          onConfirm={() => { deleteEvent(deleteTarget.id); setDeleteTarget(null); }}
        />
      )}
      {modalDraft && (
        <EventModal
          initial={modalDraft}
          onClose={() => setModalDraft(null)}
          onDelete={(id) => { deleteEvent(id); setModalDraft(null); }}
          onSave={(saved) => {
            upsertEvent(saved);
            setSelectedDate(saved.date);
            setModalDraft(null);
          }}
        />
      )}
    </div>
  );
}

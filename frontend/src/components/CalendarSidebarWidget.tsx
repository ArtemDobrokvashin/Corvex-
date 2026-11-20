import { DragEvent, FormEvent, useEffect, useMemo, useState, type CSSProperties } from 'react';
import {
  Bell,
  Calendar as CalendarIcon,
  Clock,
  Download,
  ExternalLink,
  Mail,
  MapPin,
  Maximize2,
  Pencil,
  Plus,
  Repeat,
  SlidersHorizontal,
  Trash2,
  Users,
  X,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { isServerEventId } from '../api/calendarApi';
import ConfirmEventDelete from '../features/calendar/ConfirmEventDelete';
import EventModal from '../features/calendar/EventModal';
import { localeTag, useI18n } from '../i18n';
import {
  buildEmptyEventDraft,
  buildEventDraftFromMail,
  CalendarEvent,
  CalendarEventDraft,
  downloadEventAsIcs,
  EventPriority,
  formatIsoDate,
  getEventsForDate,
  MailEventSource,
  useCalendarStore,
} from '../store/calendarStore';
import Calendar from './Calendar';
import { StatusBadge } from './ui';

export const CORVEX_MAIL_DND_MIME = 'application/x-corvex-mail';

function eventDotColor(ev: CalendarEvent): string {
  if (ev.fromMail) return '#2563EB';
  if (ev.priority === 'high') return '#EF4444';
  if (ev.priority === 'medium') return '#F59E0B';
  return '#10B981';
}

export default function CalendarSidebarWidget({
  sidebarOffsetPx,
  onClose,
}: {
  sidebarOffsetPx: number;
  onClose: () => void;
}) {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const events = useCalendarStore((s) => s.events);
  const selectedDate = useCalendarStore((s) => s.selectedDate);
  const setSelectedDate = useCalendarStore((s) => s.setSelectedDate);
  const upsertEvent = useCalendarStore((s) => s.upsertEvent);
  const deleteEvent = useCalendarStore((s) => s.deleteEvent);
  const serverSynced = useCalendarStore((s) => s.serverSynced);
  const syncError = useCalendarStore((s) => s.syncError);
  const clearSyncError = useCalendarStore((s) => s.clearSyncError);
  const resaveLocalEvents = useCalendarStore((s) => s.resaveLocalEvents);

  const [quickTitle, setQuickTitle] = useState('');
  const [quickTime, setQuickTime] = useState('14:00');
  const quickTimeValid = /^([01]\d|2[0-3]):[0-5]\d$/.test(quickTime) && quickTime !== '23:59';
  const [quickPriority, setQuickPriority] = useState<EventPriority>('medium');
  const [modalDraft, setModalDraft] = useState<CalendarEventDraft | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<CalendarEvent | null>(null);
  const [dragActive, setDragActive] = useState(false);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !modalDraft && !deleteTarget) onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [deleteTarget, modalDraft, onClose]);

  useEffect(() => {
    // Native cancellation can emit dragleave at the pointer's last position,
    // which may still be inside the widget. The source's dragend is decisive.
    const clearDrag = () => setDragActive(false);
    window.addEventListener('dragend', clearDrag);
    return () => window.removeEventListener('dragend', clearDrag);
  }, []);

  const todayIso = formatIsoDate(new Date());

  const markersByDate = useMemo(() => {
    const [yearStr, monthStr] = selectedDate.split('-');
    const year = Number(yearStr);
    const monthIndex = Number(monthStr) - 1;
    const map: Record<string, string[]> = {};
    if (!Number.isFinite(year) || !Number.isFinite(monthIndex)) return map;
    for (let d = -6; d <= 37; d += 1) {
      const iso = formatIsoDate(new Date(year, monthIndex, d));
      const dayEvents = getEventsForDate(events, iso);
      if (dayEvents.length > 0) {
        map[iso] = dayEvents.map(eventDotColor);
      }
    }
    return map;
  }, [events, selectedDate]);

  const dayEvents = useMemo(
    () => getEventsForDate(events, selectedDate),
    [events, selectedDate],
  );

  const formattedSelectedDay = useMemo(() => {
    const [y, m, d] = selectedDate.split('-').map(Number);
    if (!y || !m || !d) return selectedDate;
    return new Intl.DateTimeFormat(localeTag(locale), {
      weekday: 'short',
      day: 'numeric',
      month: 'long',
    }).format(new Date(y, m - 1, d));
  }, [locale, selectedDate]);

  const handleQuickCreate = (e: FormEvent) => {
    e.preventDefault();
    const trimmed = quickTitle.trim();
    if (!trimmed || !quickTimeValid) return;
    const draft = buildEmptyEventDraft(selectedDate, quickTime);
    if (draft.endTime <= draft.startTime) return;
    upsertEvent({
      ...draft,
      title: trimmed,
      priority: quickPriority,
      reminderMinutes: 15,
    });
    setQuickTitle('');
  };

  const handleDragOver = (e: DragEvent<HTMLDivElement>) => {
    if (e.dataTransfer.types.includes(CORVEX_MAIL_DND_MIME)) {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'copy';
      if (!dragActive) setDragActive(true);
    }
  };

  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragActive(false);
    const raw = e.dataTransfer.getData(CORVEX_MAIL_DND_MIME);
    if (!raw) return;
    try {
      const mail = JSON.parse(raw) as MailEventSource;
      if (typeof mail.id !== 'string' || typeof mail.subject !== 'string' || typeof mail.from !== 'string' || typeof mail.body !== 'string') return;
      const draft = buildEventDraftFromMail(mail);
      setModalDraft({ ...draft, date: selectedDate });
    } catch {
      // ignore malformed payload
    }
  };

  const openFullCalendar = () => {
    onClose();
    navigate(`/calendar?date=${selectedDate}`);
  };

  return (
    <>
      <aside
        aria-label={t('Виджет календаря')}
        onDragEnter={handleDragOver}
        onDragOver={handleDragOver}
        onDragLeave={(event) => {
          // dragleave bubbles for every child; relatedTarget may be null during native drags.
          const rect = event.currentTarget.getBoundingClientRect();
          if (event.clientX < rect.left || event.clientX >= rect.right || event.clientY < rect.top || event.clientY >= rect.bottom) {
            setDragActive(false);
          }
        }}
        onDrop={handleDrop}
        style={{ '--sidebar-offset': `${sidebarOffsetPx}px` } as CSSProperties}
        className="fixed bottom-14 left-0 right-0 top-[52px] z-40 flex w-full flex-col border-r border-line bg-surface shadow-pop md:bottom-0 md:left-[var(--sidebar-offset)] md:right-auto md:top-0 md:w-[360px]"
      >
        {/* 56px Header */}
        <header className="flex h-14 shrink-0 items-center justify-between gap-2 border-b border-line px-3.5">
          <div className="flex min-w-0 items-center gap-2">
            <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-[#EEF4FF] text-accent">
              <CalendarIcon size={15} strokeWidth={1.9} />
            </span>
            <span className="truncate text-[14px] font-semibold text-ink">{t('Мой день')}</span>
            {selectedDate !== todayIso && (
              <button
                type="button"
                onClick={() => setSelectedDate(todayIso)}
                className="rounded-md border border-line-control bg-canvas px-2 py-0.5 text-[11px] font-medium text-ink-soft hover:bg-surface"
              >
                {t('Сегодня')}
              </button>
            )}
          </div>

          <div className="flex shrink-0 items-center gap-1">
            <button
              type="button"
              onClick={() => setModalDraft(buildEmptyEventDraft(selectedDate))}
              title={t('Новое событие')}
              aria-label={t('Новое событие')}
              className="toolbar-btn text-accent hover:bg-[#EEF4FF] hover:text-accent-dark"
            >
              <Plus size={17} strokeWidth={2} />
            </button>
            <button
              type="button"
              onClick={openFullCalendar}
              title={t('Открыть полный календарь')}
              className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-line-control bg-surface px-2.5 text-xs font-medium text-ink-soft transition hover:border-[#CBD5E1] hover:bg-canvas hover:text-ink"
            >
              <Maximize2 size={13} strokeWidth={1.9} />
              <span>{t('Весь календарь')}</span>
            </button>
            <button
              type="button"
              onClick={onClose}
              aria-label={t('Скрыть виджет календаря')}
              title={t('Скрыть виджет календаря')}
              className="toolbar-btn"
            >
              <X size={16} />
            </button>
          </div>
        </header>

        {/* Ошибка сохранения была видна только на полной странице календаря:
            событие, созданное здесь, оставалось на экране, хотя сервер его
            отверг. Виджет обязан говорить то же, что и страница. */}
        {syncError && (
          <div role="alert" className="flex shrink-0 items-start justify-between gap-2 border-b border-amber-200 bg-amber-50 px-3.5 py-2 text-[11px] leading-snug text-amber-900">
            <span className="min-w-0">{t(syncError)}</span>
            <span className="flex shrink-0 items-center gap-1.5">
              {serverSynced && (
                <button type="button" onClick={() => { void resaveLocalEvents(); }} className="font-medium underline">
                  {t('Повторить')}
                </button>
              )}
              <button type="button" onClick={clearSyncError} aria-label={t('Закрыть')} className="px-1 text-amber-700">×</button>
            </span>
          </div>
        )}

        <div className="scroll-area flex-1 divide-y divide-line-subtle">
          {/* One stable drop target covers widget children only while an email is dragged.
              This avoids losing a native drop when the pointer crosses child boundaries. */}
          {dragActive && (
            <div aria-hidden="true" className="absolute inset-0 z-20 bg-accent/[0.04]">
              <div className="pointer-events-none absolute inset-x-3 top-16 rounded-xl border-2 border-dashed border-accent bg-[#EEF4FF] p-4 text-center text-xs font-medium text-accent-dark shadow-soft">
                <Mail size={18} className="mx-auto mb-1 text-accent" />
                {t('Отпустите письмо, чтобы запланировать событие на выбранный день')}
              </div>
            </div>
          )}

          {/* Mini-month calendar */}
          <div className="px-3.5 py-3">
            <Calendar
              selectedDate={selectedDate}
              onSelectDate={setSelectedDate}
              markersByDate={markersByDate}
            />
          </div>

          {/* Quick Add Event */}
          <form onSubmit={handleQuickCreate} className="space-y-2 bg-canvas/60 px-3.5 py-3">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold uppercase tracking-[0.06em] text-muted">
                {t('Быстрое добавление')}
              </span>
              <button
                type="button"
                onClick={() => setModalDraft({
                  ...buildEmptyEventDraft(selectedDate, quickTime),
                  title: quickTitle,
                  priority: quickPriority,
                })}
                className="inline-flex items-center gap-1 text-[11.5px] font-medium text-accent hover:text-accent-dark"
              >
                <SlidersHorizontal size={12} /> {t('Все параметры')}
              </button>
            </div>
            <div className="flex gap-1.5">
              <input
                value={quickTitle}
                onChange={(e) => setQuickTitle(e.target.value)}
                placeholder={t('Название встречи или задачи…')}
                className="field h-8 flex-1 text-xs"
              />
              <button
                type="submit"
                disabled={!quickTitle.trim() || !quickTimeValid}
                aria-label={t('Добавить событие')}
                className="btn-primary h-8 px-2.5 text-xs"
              >
                <Plus size={14} />
              </button>
            </div>
            <div className="flex items-center justify-between gap-2">
              <input
                type="time"
                required
                max="23:58"
                value={quickTime}
                onChange={(e) => setQuickTime(e.target.value)}
                aria-label={t('Время начала')}
                aria-invalid={!quickTimeValid}
                className="h-7 rounded-md border border-line-control bg-surface px-2 text-xs text-ink tabular-nums"
              />
              <div className="flex items-center gap-1" role="group" aria-label={t('Приоритет')}>
                {([
                  { id: 'high', label: t('Выс.'), dot: 'bg-[#EF4444]' },
                  { id: 'medium', label: t('Сред.'), dot: 'bg-[#F59E0B]' },
                  { id: 'low', label: t('Низк.'), dot: 'bg-[#10B981]' },
                ] as const).map((p) => (
                  <button
                    key={p.id}
                    type="button"
                    aria-pressed={quickPriority === p.id}
                    onClick={() => setQuickPriority(p.id)}
                    className={`inline-flex h-7 items-center gap-1 rounded-md border px-2 text-[11px] font-medium transition ${
                      quickPriority === p.id
                        ? 'border-accent/40 bg-[#EEF4FF] text-accent-dark'
                        : 'border-line-control bg-surface text-muted hover:text-ink'
                    }`}
                  >
                    <span className={`h-1.5 w-1.5 rounded-full ${p.dot}`} />
                    <span>{p.label}</span>
                  </button>
                ))}
              </div>
            </div>
            {!quickTimeValid && <p role="alert" className="text-xs text-danger">{t('Укажите время начала до 23:59.')}</p>}
          </form>

          {/* Selected Day Agenda */}
          <div className="px-3.5 py-3">
            <div className="mb-2.5 flex items-center justify-between">
              <span className="text-[12.5px] font-semibold capitalize text-ink">
                {formattedSelectedDay}
              </span>
              <span className="rounded-full bg-canvas px-2 py-0.5 text-[11px] font-semibold text-muted tabular-nums">
                {dayEvents.length}
              </span>
            </div>

            {dayEvents.length === 0 ? (
              <div className="rounded-xl border border-dashed border-line-control bg-canvas px-3 py-6 text-center">
                <p className="text-xs font-medium text-muted">{t('На этот день событий нет')}</p>
                <p className="mt-1 text-[11px] text-faint">
                  {t('Создайте встречу вручную или перетащите письмо из списка')}
                </p>
              </div>
            ) : (
              <div className="space-y-2">
                {dayEvents.map((ev) => {
                  const barColor = eventDotColor(ev);
                  return (
                    <article
                      key={`${ev.id}-${selectedDate}`}
                      className="group relative rounded-xl border border-line-control bg-surface p-2.5 transition hover:border-[#CBD5E1]"
                    >
                      <span
                        className="absolute bottom-2.5 left-0 top-2.5 w-[3px] rounded-r"
                        style={{ backgroundColor: barColor }}
                        aria-hidden="true"
                      />
                      <div className="pl-2">
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <h4 className="text-[13px] font-semibold leading-snug text-ink">
                              {ev.title}
                            </h4>
                            {serverSynced && !isServerEventId(ev.id) && (
                              <p className="mt-0.5 text-[10px] font-medium uppercase tracking-wide text-amber-700">
                                {t('Не сохранено на сервере')}
                              </p>
                            )}
                          </div>
                          <div className="flex shrink-0 items-center gap-0.5 opacity-0 transition group-hover:opacity-100 focus-within:opacity-100">
                            <button
                              type="button"
                              onClick={() => {
                                const source = events.find((event) => event.id === ev.id) ?? ev;
                                setModalDraft({
                                id: source.id,
                                title: ev.title,
                                date: source.date,
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
                              }}
                              aria-label={`${t('Редактировать')}: ${ev.title}`}
                              className="rounded p-1 text-faint hover:bg-canvas hover:text-ink"
                            >
                              <Pencil size={13} />
                            </button>
                            <button
                              type="button"
                              onClick={() => downloadEventAsIcs(events.find((event) => event.id === ev.id) ?? ev)}
                              aria-label={`${t('Экспорт .ics')}: ${ev.title}`}
                              title={t('Экспорт .ics')}
                              className="rounded p-1 text-faint hover:bg-canvas hover:text-ink"
                            >
                              <Download size={13} />
                            </button>
                            <button
                              type="button"
                              onClick={() => setDeleteTarget(ev)}
                              aria-label={`${t('Удалить')}: ${ev.title}`}
                              className="rounded p-1 text-faint hover:bg-danger-soft hover:text-danger"
                            >
                              <Trash2 size={13} />
                            </button>
                          </div>
                        </div>

                        <div className="mt-1 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[11.5px] text-muted tabular-nums">
                          <span className="inline-flex items-center gap-1 font-medium text-ink-soft">
                            <Clock size={11.5} className="text-faint" />
                            {ev.allDay ? t('Весь день') : `${ev.startTime} – ${ev.endTime}`}
                          </span>

                          {ev.reminderMinutes !== null && (
                            <span className="inline-flex items-center gap-1">
                              <Bell size={11} className="text-faint" />
                              {ev.reminderMinutes === 0
                                ? t('В начале')
                                : t('{min} мин', { min: ev.reminderMinutes })}
                            </span>
                          )}

                          {ev.recurrence !== 'none' && (
                            <span className="inline-flex items-center gap-1">
                              <Repeat size={11} className="text-faint" />
                              {t('Повтор')}
                            </span>
                          )}
                        </div>

                        {ev.location && (
                          <div className="mt-1 flex items-center gap-1 text-[11.5px] text-muted">
                            <MapPin size={11.5} className="shrink-0 text-faint" />
                            <span className="truncate">{ev.location}</span>
                          </div>
                        )}

                        <div className="mt-2 flex flex-wrap items-center gap-1.5">
                          <StatusBadge
                            tone={ev.priority === 'high' ? 'critical' : ev.priority === 'medium' ? 'warning' : 'positive'}
                          >
                            {ev.priority === 'high' ? t('Высокий') : ev.priority === 'medium' ? t('Средний') : t('Низкий')}
                          </StatusBadge>

                          {ev.attendees.length > 0 && (
                            <span className="inline-flex items-center gap-1 rounded-md bg-canvas px-1.5 py-0.5 text-[11px] text-muted">
                              <Users size={11} /> {ev.attendees.length}
                            </span>
                          )}

                          {ev.fromMail && (
                            ev.sourceMailId ? (
                              <button
                                type="button"
                                onClick={() => {
                                  onClose();
                                  navigate(`/mail?mailId=${encodeURIComponent(ev.sourceMailId!)}`);
                                }}
                                className="inline-flex items-center gap-1 rounded-md bg-[#EFF6FF] px-1.5 py-0.5 text-[11px] font-medium text-[#1D4ED8] hover:underline"
                              >
                                <Mail size={11} /> {t('К письму')}
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
                })}
              </div>
            )}
          </div>
        </div>

        {/* Footer button to open full /calendar */}
        <footer className="shrink-0 border-t border-line bg-canvas px-3.5 py-2.5">
          <p className="mb-2 text-[11px] leading-relaxed text-muted">
            {serverSynced
              ? t('Серверные напоминания включены: они приходят на почту даже при закрытом браузере.')
              : t('События сохраняются только в этом браузере и не синхронизируются. Напоминания работают, пока приложение открыто.')}
          </p>
          <button
            type="button"
            onClick={openFullCalendar}
            className="flex h-9 w-full items-center justify-center gap-2 rounded-lg border border-line-control bg-surface text-[13px] font-medium text-ink transition hover:border-accent/40 hover:bg-[#EEF4FF] hover:text-accent-dark"
          >
            <ExternalLink size={14} />
            <span>{t('Открыть полный календарь')}</span>
          </button>
        </footer>
      </aside>

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
          onOpenFullCalendar={(currentDraft) => {
            setSelectedDate(currentDraft.date);
            setModalDraft(null);
            onClose();
            navigate(`/calendar?date=${currentDraft.date}`, { state: { eventDraft: currentDraft } });
          }}
        />
      )}
    </>
  );
}

import { FormEvent, useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Bell,
  Calendar as CalendarIcon,
  Clock,
  ExternalLink,
  Mail,
  MapPin,
  Plus,
  Repeat,
  Trash2,
  Users,
  X,
} from 'lucide-react';
import { cleanPersonName, StatusBadge } from '../../components/ui';
import { useI18n } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import {
  CalendarEventDraft,
  EventBusyStatus,
  EventPriority,
  EventRecurrence,
  findConflictingEvents,
  useCalendarStore,
} from '../../store/calendarStore';

const COLLEAGUE_SUGGESTIONS = [
  'a.petrova@company.local',
  'd.kozlov@company.local',
  'it@company.local',
  'hr@company.local',
];

export default function EventModal({
  initial,
  onClose,
  onSave,
  onDelete,
  onOpenFullCalendar,
}: {
  initial: CalendarEventDraft;
  onClose: () => void;
  onSave: (draft: CalendarEventDraft) => void;
  onDelete?: (id: string) => void;
  onOpenFullCalendar?: (draft: CalendarEventDraft) => void;
}) {
  const { t } = useI18n();
  const events = useCalendarStore((s) => s.events);
  const serverSynced = useCalendarStore((s) => s.serverSynced);
  const demoMode = useSession((s) => s.demoMode);
  const [draft, setDraft] = useState<CalendarEventDraft>(initial);
  const [attendeeInput, setAttendeeInput] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const conflicts = useMemo(
    () => findConflictingEvents(events, draft),
    [events, draft],
  );

  const addAttendee = (raw: string) => {
    const value = raw.trim();
    if (!value) return;
    if (!draft.attendees.includes(value)) {
      setDraft({ ...draft, attendees: [...draft.attendees, value] });
    }
    setAttendeeInput('');
  };

  const removeAttendee = (target: string) => {
    setDraft({ ...draft, attendees: draft.attendees.filter((item) => item !== target) });
  };

  const invalidTimeRange = !draft.allDay && (!draft.startTime || !draft.endTime || draft.endTime <= draft.startTime);

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!draft.title.trim() || invalidTimeRange) return;
    // Hidden time fields still need valid values when an all-day event is restored.
    const allDayNeedsDefaults = draft.allDay && (!draft.startTime || !draft.endTime || draft.endTime <= draft.startTime);
    onSave(allDayNeedsDefaults ? { ...draft, startTime: '09:00', endTime: '10:00' } : draft);
  };

  const priorityOptions: Array<{ value: EventPriority; label: string; dot: string; activeClass: string }> = [
    { value: 'high', label: t('Высокий'), dot: 'bg-[#EF4444]', activeClass: 'border-[#FCA5A5] bg-[#FEE2E2] text-[#B91C1C]' },
    { value: 'medium', label: t('Средний'), dot: 'bg-[#F59E0B]', activeClass: 'border-[#FCD34D] bg-[#FEF3C7] text-[#92400E]' },
    { value: 'low', label: t('Низкий'), dot: 'bg-[#10B981]', activeClass: 'border-[#86EFAC] bg-[#DCFCE7] text-[#15803D]' },
  ];

  const reminderOptions: Array<{ value: string; minutes: number | null; label: string }> = [
    { value: 'none', minutes: null, label: t('Без напоминания') },
    { value: '0', minutes: 0, label: t('В момент начала') },
    { value: '5', minutes: 5, label: t('За 5 минут') },
    { value: '15', minutes: 15, label: t('За 15 минут') },
    { value: '30', minutes: 30, label: t('За 30 минут') },
    { value: '60', minutes: 60, label: t('За 1 час') },
    { value: '1440', minutes: 1440, label: t('За 1 день') },
  ];

  const recurrenceOptions: Array<{ value: EventRecurrence; label: string }> = [
    { value: 'none', label: t('Не повторять') },
    { value: 'daily', label: t('Ежедневно') },
    { value: 'weekdays', label: t('По будням (Пн–Пт)') },
    { value: 'weekly', label: t('Еженедельно') },
    { value: 'monthly', label: t('Ежемесячно') },
  ];

  const busyOptions: Array<{ value: EventBusyStatus; label: string }> = [
    { value: 'busy', label: t('Занят') },
    { value: 'tentative', label: t('Под вопросом') },
    { value: 'free', label: t('Свободен') },
  ];

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 p-4 backdrop-blur-[1px]"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={draft.id ? t('Редактировать событие') : t('Новое событие')}
        onClick={(e) => e.stopPropagation()}
        className="flex max-h-[92dvh] w-full max-w-[560px] animate-scale-in flex-col overflow-hidden rounded-xl border border-line-control bg-surface shadow-pop"
      >
        <header className="flex h-[52px] shrink-0 items-center justify-between border-b border-line px-5 py-3.5">
          <div className="flex items-center gap-2.5">
            <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-[#EFF6FF] text-accent">
              <CalendarIcon size={17} strokeWidth={1.9} />
            </span>
            <div>
              <h2 className="text-[15px] font-semibold text-ink">
                {draft.id ? t('Редактировать событие') : t('Новое событие')}
              </h2>
              {draft.fromMail && (
                <div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted">
                  <StatusBadge tone="info">
                    <Mail size={11} /> {t('Из письма')}
                  </StatusBadge>
                  {draft.sourceMailSender && (
                    <span className="truncate">{cleanPersonName(draft.sourceMailSender)}</span>
                  )}
                </div>
              )}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('Закрыть')}
            className="icon-btn-sm"
          >
            <X size={17} />
          </button>
        </header>

        <form onSubmit={handleSubmit} className="scroll-area flex-1 space-y-4 px-5 py-4">
          {initial.id && initial.recurrence !== 'none' && (
            <p className="rounded-lg border border-accent/20 bg-accent-soft px-3 py-2 text-xs text-accent-dark">
              {t('Редактируется вся серия. Указана дата начала повторения.')}
            </p>
          )}
          <div>
            <label htmlFor="event-title" className="field-label">{t('Название события')}</label>
            <input
              id="event-title"
              autoFocus
              required
              value={draft.title}
              onChange={(e) => setDraft({ ...draft, title: e.target.value })}
              placeholder={t('Например: Встреча по бюджету с бухгалтерией')}
              className="field"
            />
          </div>

          {/* Дата и время */}
          <div className="grid gap-3 sm:grid-cols-[1fr_auto_auto]">
            <div>
              <label htmlFor="event-date" className="field-label">{t('Дата')}</label>
              <input
                id="event-date"
                type="date"
                required
                value={draft.date}
                onChange={(e) => setDraft({ ...draft, date: e.target.value })}
                className="field tabular-nums"
              />
            </div>

            {!draft.allDay && (
              <>
                <div>
                  <label htmlFor="event-start" className="field-label">{t('Начало')}</label>
                  <input
                    id="event-start"
                    type="time"
                    value={draft.startTime}
                    onChange={(e) => setDraft({ ...draft, startTime: e.target.value })}
                    className="field w-full sm:w-[116px] tabular-nums"
                  />
                </div>
                <div>
                  <label htmlFor="event-end" className="field-label">{t('Окончание')}</label>
                  <input
                    id="event-end"
                    type="time"
                    value={draft.endTime}
                    onChange={(e) => setDraft({ ...draft, endTime: e.target.value })}
                    className="field w-full sm:w-[116px] tabular-nums"
                  />
                </div>
              </>
            )}
          </div>

          {invalidTimeRange && <p role="alert" className="text-xs text-danger">{t('Окончание должно быть позже начала.')}</p>}

          <div className="flex flex-wrap items-center justify-between gap-3">
            <label className="inline-flex cursor-pointer items-center gap-2 text-[13px] font-medium text-ink-soft">
              <input
                type="checkbox"
                checked={draft.allDay}
                onChange={(e) => setDraft({ ...draft, allDay: e.target.checked })}
                className="h-4 w-4 rounded border-line-control accent-[#1D63ED]"
              />
              <Clock size={14} className="text-faint" />
              <span>{t('Весь день')}</span>
            </label>

            <label className="inline-flex cursor-pointer items-center gap-2 text-[13px] font-medium text-ink-soft">
              <input
                type="checkbox"
                checked={draft.fromMail}
                onChange={(e) => setDraft({ ...draft, fromMail: e.target.checked })}
                className="h-4 w-4 rounded border-line-control accent-[#1D63ED]"
              />
              <Mail size={14} className="text-[#2563EB]" />
              <span>{t('Слой «Из писем»')}</span>
            </label>
          </div>

          {conflicts.length > 0 && (
            <div className="flex items-start gap-2 rounded-lg border border-[#FDE68A] bg-[#FFFBEB] px-3 py-2 text-xs text-[#92400E]">
              <AlertTriangle size={15} className="mt-0.5 shrink-0" />
              <div>
                <span className="font-semibold">{t('Пересечение в расписании:')}</span>{' '}
                {conflicts.map((c) => `${c.title} (${c.startTime}–${c.endTime})`).join(', ')}
              </div>
            </div>
          )}

          {/* Приоритет */}
          <div>
            <span className="field-label">{t('Приоритет')}</span>
            <div className="grid grid-cols-3 gap-2" role="group" aria-label={t('Приоритет')}>
              {priorityOptions.map((opt) => {
                const selected = draft.priority === opt.value;
                return (
                  <button
                    key={opt.value}
                    type="button"
                    aria-pressed={selected}
                    onClick={() => setDraft({ ...draft, priority: opt.value })}
                    className={`flex h-9 items-center justify-center gap-2 rounded-lg border text-[13px] font-medium transition ${
                      selected
                        ? opt.activeClass
                        : 'border-line-control bg-surface text-muted hover:bg-canvas hover:text-ink'
                    }`}
                  >
                    <span className={`h-2 w-2 rounded-full ${opt.dot}`} />
                    <span>{opt.label}</span>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Напоминание и Повторение */}
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <label htmlFor="event-reminder" className="field-label flex items-center gap-1.5">
                <Bell size={13} className="text-faint" />
                <span>{t('Напоминание')}</span>
              </label>
              <select
                id="event-reminder"
                value={draft.reminderMinutes === null ? 'none' : String(draft.reminderMinutes)}
                onChange={(e) => {
                  const val = e.target.value;
                  setDraft({ ...draft, reminderMinutes: val === 'none' ? null : Number(val) });
                }}
                className="field"
              >
                {reminderOptions.map((opt) => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
              {serverSynced && draft.reminderMinutes !== null && (
                <p className="mt-1 text-[11px] leading-relaxed text-faint">
                  {t('Напоминание придёт на почту и при закрытом браузере.')}
                </p>
              )}
            </div>

            <div>
              <label htmlFor="event-recurrence" className="field-label flex items-center gap-1.5">
                <Repeat size={13} className="text-faint" />
                <span>{t('Повторение')}</span>
              </label>
              <select
                id="event-recurrence"
                value={draft.recurrence}
                onChange={(e) => setDraft({ ...draft, recurrence: e.target.value as EventRecurrence })}
                className="field"
              >
                {recurrenceOptions.map((opt) => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
            </div>
          </div>

          {/* Место / Видеозвонок и Статус занятости */}
          <div className="grid gap-3 sm:grid-cols-[1fr_168px]">
            <div>
              <div className="mb-1.5 flex items-center justify-between">
                <label htmlFor="event-location" className="text-[13px] font-medium text-ink-soft flex items-center gap-1.5">
                  <MapPin size={13} className="text-faint" />
                  <span>{t('Место или ссылка')}</span>
                </label>
              </div>
              <input
                id="event-location"
                value={draft.location}
                onChange={(e) => setDraft({ ...draft, location: e.target.value })}
                placeholder={t('Переговорная или ссылка на звонок')}
                className="field"
              />
            </div>

            <div>
              <label htmlFor="event-busy" className="field-label">{t('Статус времени')}</label>
              <select
                id="event-busy"
                value={draft.busyStatus}
                onChange={(e) => setDraft({ ...draft, busyStatus: e.target.value as EventBusyStatus })}
                className="field"
              >
                {busyOptions.map((opt) => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
            </div>
          </div>

          {/* Участники */}
          <div>
            <label htmlFor="event-attendees" className="field-label flex items-center gap-1.5">
              <Users size={13} className="text-faint" />
              <span>{t('Участники')}</span>
            </label>
            <div className="flex gap-2">
              <input
                id="event-attendees"
                value={attendeeInput}
                onChange={(e) => setAttendeeInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ',') {
                    e.preventDefault();
                    addAttendee(attendeeInput);
                  }
                }}
                placeholder={t('Введите e-mail коллеги и нажмите Enter')}
                className="field flex-1"
              />
              <button
                type="button"
                onClick={() => addAttendee(attendeeInput)}
                className="btn-secondary shrink-0 px-3"
              >
                <Plus size={15} /> {t('Добавить')}
              </button>
            </div>

            {draft.attendees.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {draft.attendees.map((person) => (
                  <span key={person} className="chip chip-accent">
                    <span>{person}</span>
                    <button
                      type="button"
                      onClick={() => removeAttendee(person)}
                      aria-label={`${t('Удалить')}: ${person}`}
                      className="rounded p-0.5 hover:text-danger"
                    >
                      <X size={12} />
                    </button>
                  </span>
                ))}
              </div>
            )}

            {demoMode && <div className="mt-2 flex flex-wrap items-center gap-1.5">
              <span className="text-[11px] text-faint">{t('Быстро добавить:')}</span>
              {COLLEAGUE_SUGGESTIONS.filter((c) => !draft.attendees.includes(c)).map((colleague) => (
                <button
                  key={colleague}
                  type="button"
                  onClick={() => addAttendee(colleague)}
                  className="rounded-md border border-line-control bg-canvas px-2 py-0.5 text-[11px] text-muted transition hover:border-[#CBD5E1] hover:text-ink"
                >
                  + {colleague.split('@')[0]}
                </button>
              ))}
            </div>}
          </div>

          {/* Заметки / повестка */}
          <div>
            <label htmlFor="event-description" className="field-label">{t('Заметки и повестка')}</label>
            <textarea
              id="event-description"
              rows={3}
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              placeholder={t('Повестка встречи, вопросы или детали из письма…')}
              className="field"
            />
          </div>

          {confirmDelete && draft.id && onDelete && (
            <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-danger" role="alert">
              <p>{draft.recurrence === 'none' ? t('Удалить это событие?') : t('Удалить всю серию повторяющихся событий?')}</p>
              <div className="mt-2 flex gap-2"><button type="button" className="btn-secondary h-8 text-xs" onClick={() => setConfirmDelete(false)}>{t('Отмена')}</button><button type="button" className="btn-danger-ghost h-8 border border-red-200 text-xs" onClick={() => onDelete(draft.id!)}>{t('Удалить')}</button></div>
            </div>
          )}
          <footer className="sticky bottom-[-16px] z-10 -mx-5 flex flex-wrap items-center justify-between gap-2 border-t border-line bg-surface px-5 pb-4 pt-4">
            {draft.id && onDelete && !confirmDelete && <button type="button" className="btn-danger-ghost h-8 px-2 text-xs" onClick={() => setConfirmDelete(true)}><Trash2 size={14} />{t('Удалить')}</button>}
            {onOpenFullCalendar ? (
              <button
                type="button"
                onClick={() => onOpenFullCalendar(draft)}
                className="inline-flex items-center gap-1.5 text-xs font-medium text-accent hover:text-accent-dark"
              >
                <ExternalLink size={14} /> {t('Открыть в полном календаре')}
              </button>
            ) : (
              <span />
            )}
            <div className="ml-auto flex items-center gap-2">
              <button type="button" onClick={onClose} className="btn-secondary">
                {t('Отмена')}
              </button>
              <button type="submit" className="btn-primary" disabled={invalidTimeRange}>
                {draft.id ? t('Сохранить изменения') : t('Сохранить событие')}
              </button>
            </div>
          </footer>
        </form>
      </div>
    </div>
  );
}

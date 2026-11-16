import { apiFetch } from './httpClient';
import type { CalendarEventItem } from '../store/calendarStore';

/**
 * Контракт календаря (gateway: /api/v1/calendar/events).
 *
 * Сервер хранит момент времени в UTC и IANA-зону события. Клиентский тип
 * хранит дату и «HH:MM» по местному времени, поэтому конвертация выполняется
 * через зону события, а не через зону браузера: иначе событие, созданное в
 * другом часовом поясе, показывалось бы со сдвигом.
 */
export interface CalendarEventView {
  id: string;
  revision: number;
  title: string;
  description: string;
  location: string;
  startsAt: string;
  endsAt: string;
  allDay: boolean;
  timeZone: string;
  priority: CalendarEventItem['priority'];
  busyStatus: CalendarEventItem['busyStatus'];
  recurrence: CalendarEventItem['recurrence'];
  reminderMinutes: number | null;
  attendees: string[];
  source: { kind: 'NONE' | 'MAIL' | 'CHAT'; mailId: string | null; subject: string | null; sender: string | null };
  createdAt: string;
  updatedAt: string;
}

export interface CalendarEventSave {
  title: string;
  description: string;
  location: string;
  startsAt: string;
  endsAt: string;
  allDay: boolean;
  timeZone: string;
  priority: CalendarEventItem['priority'];
  busyStatus: CalendarEventItem['busyStatus'];
  recurrence: CalendarEventItem['recurrence'];
  reminderMinutes: number | null;
  attendees: string[];
  source: CalendarEventView['source'];
  revision?: number;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Локальный id имеет вид ev-…; только серверные UUID можно менять через API. */
export const isServerEventId = (id: string) => UUID.test(id);

export const localTimeZone = () => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
};

const pad = (value: number) => String(value).padStart(2, '0');

/** Смещение зоны в конкретный момент: важно для дат вокруг перехода на летнее время. */
function zoneOffsetMs(utcMs: number, timeZone: string): number {
  const formatted = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(utcMs));
  const part = (type: string) => Number(formatted.find((item) => item.type === type)?.value ?? 0);
  const hour = part('hour') === 24 ? 0 : part('hour');
  const asUtc = Date.UTC(part('year'), part('month') - 1, part('day'), hour, part('minute'), part('second'));
  return asUtc - utcMs;
}

/** «2026-10-19» + «09:30» в зоне timeZone → ISO instant. */
export function zonedToInstant(date: string, time: string, timeZone: string): string {
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = time.split(':').map(Number);
  const guess = Date.UTC(year, (month ?? 1) - 1, day ?? 1, hour ?? 0, minute ?? 0);
  // Два прохода: смещение зависит от самого момента, поэтому первого приближения
  // недостаточно у границы перехода времени.
  let instant = guess - zoneOffsetMs(guess, timeZone);
  instant = guess - zoneOffsetMs(instant, timeZone);
  return new Date(instant).toISOString();
}

/** ISO instant → дата и время в зоне timeZone. */
export function instantToZoned(instant: string, timeZone: string): { date: string; time: string } {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).formatToParts(new Date(instant));
  const part = (type: string) => parts.find((item) => item.type === type)?.value ?? '00';
  const hour = part('hour') === '24' ? '00' : part('hour');
  return { date: `${part('year')}-${part('month')}-${part('day')}`, time: `${hour}:${part('minute')}` };
}

export function viewToItem(view: CalendarEventView): CalendarEventItem {
  const timeZone = view.timeZone || localTimeZone();
  const start = instantToZoned(view.startsAt, timeZone);
  const end = instantToZoned(view.endsAt, timeZone);
  return {
    id: view.id,
    title: view.title,
    date: start.date,
    startTime: view.allDay ? '00:00' : start.time,
    endTime: view.allDay ? '23:59' : end.time,
    allDay: view.allDay,
    priority: view.priority,
    fromMail: view.source?.kind === 'MAIL',
    sourceMailId: view.source?.mailId ?? undefined,
    sourceMailSubject: view.source?.subject ?? undefined,
    sourceMailSender: view.source?.sender ?? undefined,
    reminderMinutes: view.reminderMinutes,
    recurrence: view.recurrence,
    busyStatus: view.busyStatus,
    location: view.location,
    attendees: [...view.attendees],
    description: view.description,
    createdAt: view.createdAt,
    revision: view.revision,
  };
}

export function itemToSave(item: CalendarEventItem, timeZone = localTimeZone()): CalendarEventSave {
  const startTime = item.allDay ? '00:00' : item.startTime;
  const endTime = item.allDay ? '23:59' : item.endTime;
  return {
    title: item.title,
    description: item.description,
    location: item.location,
    startsAt: zonedToInstant(item.date, startTime, timeZone),
    endsAt: zonedToInstant(item.date, endTime, timeZone),
    allDay: item.allDay,
    timeZone,
    priority: item.priority,
    busyStatus: item.busyStatus,
    recurrence: item.recurrence,
    reminderMinutes: item.reminderMinutes,
    attendees: item.attendees,
    source: {
      kind: item.fromMail ? 'MAIL' : 'NONE',
      mailId: item.fromMail ? item.sourceMailId ?? null : null,
      subject: item.fromMail ? item.sourceMailSubject ?? null : null,
      sender: item.fromMail ? item.sourceMailSender ?? null : null,
    },
    // Ревизия отправляется только для события, которое уже есть на сервере:
    // иначе сервер справедливо отклонит сохранение как устаревшее.
    revision: isServerEventId(item.id) ? item.revision : undefined,
  };
}

const rangeQuery = (fromIso: string, toIso: string) =>
  `?from=${encodeURIComponent(fromIso)}&to=${encodeURIComponent(toIso)}`;

export const calendarApi = {
  list: (fromIso: string, toIso: string) =>
    apiFetch<CalendarEventView[]>(`/api/v1/calendar/events${rangeQuery(fromIso, toIso)}`),
  create: (body: CalendarEventSave) =>
    apiFetch<CalendarEventView>('/api/v1/calendar/events', { method: 'POST', body: JSON.stringify(body) }),
  update: (id: string, body: CalendarEventSave) =>
    apiFetch<CalendarEventView>(`/api/v1/calendar/events/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  remove: (id: string, revision?: number) =>
    apiFetch<void>(`/api/v1/calendar/events/${id}${revision != null ? `?revision=${revision}` : ''}`, { method: 'DELETE' }),
};

/** Окно синхронизации: два месяца назад и пять вперёд покрывают все виды календаря. */
export function syncWindow(now = new Date()): { from: string; to: string } {
  const from = new Date(now.getTime());
  from.setMonth(from.getMonth() - 2);
  from.setDate(1);
  const to = new Date(now.getTime());
  to.setMonth(to.getMonth() + 5);
  to.setDate(1);
  return { from: from.toISOString(), to: to.toISOString() };
}

export const formatDateKey = (date: Date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
